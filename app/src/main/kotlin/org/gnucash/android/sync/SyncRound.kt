/*
 * Copyright (c) 2026 GnuCash Pocket contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.sync

import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.model.SyncBase
import org.gnucash.android.sync.webdav.ActionOutcome
import org.gnucash.android.sync.webdav.DownloadOutcome
import org.gnucash.android.sync.webdav.ListingResult
import org.gnucash.android.sync.webdav.RemoteEntry
import org.gnucash.android.sync.webdav.WebDavClient
import org.gnucash.android.sync.webdav.succeeded

/**
 * Runs **one** sync round end to end (feature ② v2): list the peer, fetch what
 * changed, merge, apply, and write back the base/tombstone/pending state.
 *
 * The class is deliberately thin. Every decision worth arguing about already
 * lives in a pure component ([SyncRoundPlanner], [ThreeWayMerge],
 * [org.gnucash.android.sync.webdav.WebDavActions]); what remains here is I/O and
 * bookkeeping, ordered so that a crash never leaves the local book disagreeing
 * with the recorded base:
 *
 *  1. read the base snapshot, the local records, and the user's stored answers;
 *  2. list the peer — **and stop if the listing is incomplete** (nothing is written);
 *  3. fetch remote content only where the etag says it may have changed;
 *  4. plan;
 *  5. apply, then commit the base/tombstone/pending write-back — and clear the resolution
 *     overrides the round consumed (feature ② v3).
 *
 * ### Why a `Stale` outcome stops the round
 *
 * A conditional write rejected with `412` means the peer changed a record after we
 * listed it, so the merge that produced *this* round's writes was based on a view
 * that no longer holds. The correct response is to end the round and re-list —
 * retrying the same write is guaranteed to fail, and continuing to apply the rest
 * of the plan would push decisions based on a stale snapshot (plan §7.6). Commits
 * already made stand: they were conditional writes the server accepted, so they
 * are still true.
 */
class SyncRound(
    private val client: WebDavClient,
    private val state: SyncStateDbAdapter,
    private val store: LocalRecordStore,
    private val types: Collection<SyncRecordType> = SyncRecordType.entries,
    private val now: () -> Long = System::currentTimeMillis,
    private val deviceId: String? = null
) {

    fun run(): SyncRoundResult {
        val snapshot = state.readSnapshot(types)
        val baseById = snapshot.records.associateBy { SyncIdentity(it.recordType, it.recordKey) }
        val local = store.list(types)
        // The user's stored answers from the conflict centre (feature ② v3). Read here so the
        // round plans against the current answers, never a copy taken before the user spoke.
        val resolutions = state.getResolutions()

        val listing = client.listRecursive()
        if (listing !is ListingResult.Complete) {
            val incomplete = listing as ListingResult.Incomplete
            return SyncRoundResult(
                completed = false,
                abandoned = true,
                abandonReason = SyncAbandon.LISTING_INCOMPLETE,
                reason = "${incomplete.reason}: ${incomplete.detail}"
            )
        }

        val remote = materialize(listing.files, baseById)

        val plan = SyncRoundPlanner.plan(
            PlanInput(
                local = local,
                remote = remote,
                bases = snapshot.records,
                now = now(),
                listingComplete = true,
                resolutions = resolutions
            )
        )
        if (plan.abandoned) {
            // The incomplete-listing shape returned above, so the only abandonment left here is
            // the empty-listing-while-records-are-known one.
            return SyncRoundResult(
                completed = false,
                abandoned = true,
                abandonReason = SyncAbandon.EMPTY_REMOTE,
                reason = "the round was abandoned (an empty listing while records are known)"
            )
        }

        // Filled by `execute` with the records this pass actually changed, so a resolution
        // override is only spent once its action landed (feature ② v3).
        val landed = mutableSetOf<SyncIdentity>()
        val outcome = execute(plan, landed)
        commitTombstones(plan)
        commitPending(snapshot.records, plan.pending)
        commitResolutions(plan, landed)
        // The reviews travel with the result instead of being reduced to a count: a count tells
        // the user "3" and nothing else, and a caller cannot re-derive which records are parked.
        return outcome.copy(reviews = plan.reviews)
    }

    // ------------------------------------------------------------------ fetching

    /**
     * Builds the peer's view of our records from the listing.
     *
     * The listing only carries etags, so a record whose etag still equals the base's
     * has not changed since we last agreed on it — its base payload *is* its current
     * content, and no `GET` is needed. Only a changed (or never-seen, or etag-less)
     * record is fetched. That is the whole reason the base stores an etag: without it
     * every round would download the entire book.
     */
    private fun materialize(
        files: List<RemoteEntry>,
        baseById: Map<SyncIdentity, SyncBase>
    ): List<RemoteState> {
        val out = ArrayList<RemoteState>(files.size)
        for (entry in files) {
            val identity = SyncLayout.parse(entry.path) ?: continue    // not one of ours
            // A type this round does not carry is not a remote record at all, as far as the
            // merge is concerned. Letting it through would make the planner want to pull it
            // every round, and the store would decline every round — a permanent, noisy
            // non-convergence with no way for the user to see why.
            if (identity.recordType !in types) continue
            val base = baseById[identity]
            val unchanged = base != null && base.etag != null &&
                entry.etag != null && base.etag == entry.etag
            val fields = if (unchanged) {
                // Fall back to a fetch if the stored base payload cannot be decoded
                // (e.g. written by an older build): correctness over a saved request.
                RecordCodec.decodeOrNull(base!!.payload) ?: fetch(entry.path)
            } else {
                fetch(entry.path)
            }
            out += RemoteState(identity, entry.etag, fields, entry.path)
        }
        return out
    }

    private fun fetch(path: String): Map<String, String>? =
        when (val outcome = client.download(path)) {
            is DownloadOutcome.Got -> RecordCodec.decodeBytesOrNull(outcome.bytes)
            // Unreadable or refused: `null` fields. The planner treats "present but
            // unreadable" as "leave it alone", never as an absence.
            is DownloadOutcome.Refused -> null
        }

    // ------------------------------------------------------------------ applying

    /**
     * Applies the plan, recording into [landed] every record it actually changed.
     *
     * [landed] is how [commitResolutions] knows that a resolution override earned its keep: a
     * forced push that came back `Stale`, or hit a transport failure, must **not** be counted —
     * clearing the override there would throw away the user's answer and re-park a conflict
     * they had already settled.
     */
    private fun execute(plan: SyncPlan, landed: MutableSet<SyncIdentity>): SyncRoundResult {
        var uploaded = 0
        var downloaded = 0
        var remoteDeleted = 0
        var localDeleted = 0
        var deferred = 0
        var failed = 0
        var restart = false

        for (item in plan.items) {
            val identity = item.identity
            when (item.verb) {
                SyncVerb.NONE -> {
                    // Re-base the (unchanged) record so its etag follows the peer's.
                    val fields = item.remoteFields
                    if (fields != null) {
                        state.putBase(
                            identity.recordType, identity.key,
                            RecordCodec.encode(fields), now(), item.remoteEtag
                        )
                    }
                }

                SyncVerb.PUSH -> {
                    val fields = item.localFields
                    if (fields == null) {
                        failed++
                        continue
                    }
                    val payload = RecordCodec.encode(fields)
                    when (val outcome = client.putCreatingCollections(
                        SyncLayout.pathFor(identity), RecordCodec.encodeBytes(fields), item.precondition
                    )) {
                        is ActionOutcome.Done -> {
                            state.putBase(identity.recordType, identity.key, payload, now(), outcome.etag)
                            uploaded++
                            landed += identity
                        }
                        is ActionOutcome.AlreadyDone -> {
                            state.putBase(identity.recordType, identity.key, payload, now(), item.remoteEtag)
                            uploaded++
                            landed += identity
                        }
                        is ActionOutcome.Stale -> restart = true
                        else -> failed++
                    }
                }

                SyncVerb.PULL -> {
                    val fields = item.remoteFields
                    if (fields == null) {
                        failed++
                        continue
                    }
                    if (store.write(RecordState(identity.recordType, identity.key, fields))) {
                        state.putBase(
                            identity.recordType, identity.key,
                            RecordCodec.encode(fields), now(), item.remoteEtag
                        )
                        downloaded++
                        landed += identity
                    } else {
                        // The store declined — something the record needs is not in the book
                        // yet. The base must stay exactly as it was: re-basing a record that
                        // was never written makes the *next* round read "local is absent,
                        // base says it was here, remote still has it" as "the user deleted
                        // it locally" and delete the peer's copy. Deferring costs a round;
                        // re-basing costs the other device's data.
                        deferred++
                    }
                }

                SyncVerb.DELETE_REMOTE -> {
                    if (client.delete(SyncLayout.pathFor(identity), item.precondition).succeeded) {
                        state.removeBase(identity.recordType, identity.key)
                        remoteDeleted++
                        landed += identity
                    } else {
                        failed++
                    }
                }

                SyncVerb.DELETE_LOCAL -> {
                    store.delete(identity)
                    state.removeBase(identity.recordType, identity.key)
                    localDeleted++
                    landed += identity
                }
            }

            if (restart) break
        }

        return SyncRoundResult(
            completed = true,
            uploaded = uploaded,
            downloaded = downloaded,
            remoteDeleted = remoteDeleted,
            localDeleted = localDeleted,
            deferred = deferred,
            failed = failed,
            restartNeeded = restart
        )
    }

    private fun commitTombstones(plan: SyncPlan) {
        for (identity in plan.tombstones) {
            state.addTombstone(identity.recordType, identity.key, now(), deviceId)
        }
    }

    /**
     * Persists the round's "watched" set.
     *
     * The marker lives on the base row (as `pendingSince`) rather than in a side
     * table, so "which records might have been deleted over there" is a property of
     * the record's own sync state and cannot drift out of step with it. A record
     * that reappeared this round is simply absent from [pending], which clears its
     * marker.
     */
    private fun commitPending(bases: List<SyncBase>, pending: Map<SyncIdentity, Long>) {
        for (base in bases) {
            val identity = SyncIdentity(base.recordType, base.recordKey)
            val since = pending[identity]
            when {
                since == null -> if (base.pendingSince != null) {
                    state.setPendingSince(identity.recordType, identity.key, null)
                }
                base.pendingSince != since -> state.setPendingSince(identity.recordType, identity.key, since)
            }
        }
    }

    /**
     * Clears the resolution overrides this round consumed (feature ② v3).
     *
     * Two disjoint reasons, and they are **not** interchangeable:
     *
     *  * [SyncPlan.resolutionsDropped] — the record is no longer parked (the sides stopped
     *    disagreeing, or it is gone). There is nothing left to resolve, so the answer is
     *    cleared outright; leaving it would let an old choice resurface on some later round
     *    and force a write the user never asked for *then*.
     *  * [SyncPlan.resolutionsApplied] ∩ [landed] — the forced action actually landed. Only
     *    then is the answer spent. A push rejected with `412`, or a pull the store declined
     *    (the record needed something not in the book yet), leaves the override in place so
     *    the next round retries it rather than re-asking a question the user already answered.
     *
     * Cleared in one transaction: a crash with half the answers consumed is indistinguishable,
     * on the next round, from "the user answered these and not those".
     */
    private fun commitResolutions(plan: SyncPlan, landed: Set<SyncIdentity>) {
        val consumed = plan.resolutionsDropped + plan.resolutionsApplied.intersect(landed)
        if (consumed.isEmpty()) return
        state.clearResolutions(consumed)
    }
}

/**
 * What one round did.
 *
 * Counts are diagnostics, not control flow: the round's safety does not depend on
 * them. [abandoned] and [restartNeeded] are the two states a caller must react to —
 * the first means "the peer could not be trusted this time, try again later", the
 * second means "the peer moved under us, re-list".
 *
 * [deferred] is worth watching for a different reason: it counts pulls this device
 * declined to apply (see [LocalRecordStore.write]). A steady non-zero value is not a
 * failure the round can fix — it means the local store cannot yet represent something
 * the peer has, and it is the only visible sign of that.
 *
 * [reviews] is the exception to "counts are diagnostics": the list is the **work item** for the
 * conflict centre. Reducing it to a number would leave the caller unable to say *which* records
 * are parked, and would put the "what did we decide" information one layer out of reach of the
 * only place that can show it.
 */
data class SyncRoundResult(
    val completed: Boolean,
    val abandoned: Boolean = false,
    /**
     * Why nothing was decided, when [abandoned]. `null` on a round that ran, and also tolerated
     * on one that did not — [SyncNotices.of] then assumes the incomplete-listing shape, whose
     * advice ("try again") is the safe one to give by default.
     */
    val abandonReason: SyncAbandon? = null,
    val reason: String? = null,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val remoteDeleted: Int = 0,
    val localDeleted: Int = 0,
    val deferred: Int = 0,
    val failed: Int = 0,
    val restartNeeded: Boolean = false,
    val reviews: List<ConflictReview> = emptyList()
) {

    /** How many records are parked for the conflict centre. Derived, so it cannot disagree. */
    val conflicts: Int get() = reviews.size

    /**
     * `true` when this round moved something.
     *
     * The steady state ("the two sides already agree") is only distinguishable from "the round
     * did not run" by the fact that it *ran* — so the caller needs this to avoid greeting a
     * perfectly healthy no-op round with a success message on every launch.
     */
    val movedAnything: Boolean
        get() = uploaded > 0 || downloaded > 0 || remoteDeleted > 0 || localDeleted > 0
}
