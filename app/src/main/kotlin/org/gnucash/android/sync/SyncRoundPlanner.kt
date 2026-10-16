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

import org.gnucash.android.model.SyncBase
import org.gnucash.android.sync.webdav.PutPrecondition

/**
 * Turns "what is here", "what the peer listed" and "what we last agreed on" into
 * one round's worth of actions (feature ② v2, plan §7).
 *
 * This is the piece that joins the three previously-built halves — the pure merge
 * kernel ([ThreeWayMerge]), the remote listing ([org.gnucash.android.sync.webdav.WebDavClient]),
 * and the write policy ([org.gnucash.android.sync.webdav.WebDavActions]) — into a
 * single decision. It is **pure**: no network, no disk, no clock (the timestamp is
 * an argument). The executor ([SyncRound]) is what touches the world; this is what
 * decides, so the risky part stays exhaustively assertable.
 *
 * ### The two gates, restated
 *
 * 1. **An incomplete listing proves nothing.** If the listing did not run to
 *    completion, the planner abandons the entire round — not just the deletions,
 *    but the uploads too. Without a full view of the peer we cannot tell a local
 *    edit from a collision with a peer edit we never saw, so "do nothing" is the
 *    only answer that cannot lose data (plan §7.3, constraint 2).
 * 2. **Deletions need confirmed absence, twice.** The round never tombstones a
 *    record merely because this listing did not mention it; that is delegated to
 *    [ThreeWayMerge.planDeletions], which demands a complete listing and a second
 *    consecutive absence.
 *
 * ### Why deletions are decided with the same kernel as everything else
 *
 * A record can be absent from the peer *and* edited here, or deleted here *and*
 * edited there. Rather than hand-write those cases, a deletion candidate is fed
 * straight through [ThreeWayMerge.decide] with the real local/remote state — which
 * is exactly the "one side deleted, the other edited" rule from plan §7.2: the
 * edit is adopted and surfaced for review. The only thing this class adds is the
 * *confirmation* gate around it.
 *
 * ### The third input: the user's own answer (feature ② v3)
 *
 * Everything above decides by rule. `PlanInput.resolutions` is the one place a
 * **human** decision enters: when the conflict centre parked a record, the user may
 * have said which side wins, and that answer travels here as an override. It is
 * consulted **only where a record would otherwise be parked** — never anywhere else —
 * so the rule-based merge is untouched for every record the user has not spoken about.
 * An override that can be honoured becomes the record's action ([SyncVerb.PUSH] /
 * [SyncVerb.PULL], or a deletion, depending on which side is *absent*); one that cannot
 * (adopting a peer payload that could not be read) is left in place and the record stays
 * parked. See [SyncPlan.resolutionsApplied] / [SyncPlan.resolutionsDropped].
 */
object SyncRoundPlanner {

    private const val UNREADABLE_NOTE = "the remote record could not be read; it was left untouched"
    private const val CONFLICT_NOTE = "both sides changed the record differently"
    private const val DELETE_VS_EDIT_NOTE = "deleted on one side, edited on the other; the edit is kept"

    fun plan(input: PlanInput): SyncPlan {
        val localById = input.local.associateBy { SyncIdentity(it.type, it.key) }
        val remoteById = input.remote.associateBy { it.identity }
        val baseById = input.bases.associateBy { SyncIdentity(it.recordType, it.recordKey) }

        // Gate 1 — an incomplete listing is not evidence of anything. Abandon wholesale.
        if (!input.listingComplete) return abandon(baseById)

        val locallyDeleted = (baseById.keys - localById.keys).map { it.token }.toSet()

        val deletions = ThreeWayMerge.planDeletions(
            watched = baseById.keys.map { it.token }.toSet(),
            present = remoteById.keys.map { it.token }.toSet(),
            previousPending = pendingOf(baseById).mapKeys { it.key.token },
            now = input.now,
            listingComplete = true,
            locallyDeleted = locallyDeleted
        )
        // Gate 2 — an empty listing while records are known is the "wrong folder / partial
        // view" shape. The kernel refuses it, and continuing would read every record as
        // remote-deleted. Abandon wholesale as well.
        if (deletions.abandoned) return abandon(baseById)

        val items = mutableListOf<SyncItem>()
        val reviews = mutableListOf<ConflictReview>()
        val tombstones = mutableSetOf<SyncIdentity>()
        val handled = mutableSetOf<SyncIdentity>()
        val pending = LinkedHashMap<SyncIdentity, Long>()

        // The user's stored answers (feature ② v3) and the ones this round turned into an
        // action. The second set is what the executor clears — and only after the action
        // lands, so a rejected write does not lose the user's choice.
        val resolutions = input.resolutions
        val appliedResolutions = mutableSetOf<SyncIdentity>()

        // ---- 1. Confirmed deletions ------------------------------------------
        for (token in deletions.tombstones) {
            val id = SyncIdentity.fromToken(token) ?: continue
            handled += id
            val base = baseById[id]
            val local = localById[id]
            val remote = remoteById[id]

            val decision = ThreeWayMerge.decide(
                type = id.recordType,
                key = id.key,
                base = base?.let { toState(it) },
                local = local,
                remote = remote?.let { RecordState(id.recordType, id.key, it.fields ?: emptyMap()) }
            )

            when (decision) {
                // Remote is gone, local is untouched: adopt the delete here.
                MergeDecision.DeleteLocal -> {
                    tombstones += id
                    items += SyncItem(id, decision, SyncVerb.DELETE_LOCAL)
                }
                // We deleted it here, remote is untouched: propagate the delete.
                MergeDecision.DeleteRemote -> {
                    tombstones += id
                    items += SyncItem(
                        id, decision, SyncVerb.DELETE_REMOTE,
                        remoteEtag = remote?.etag, precondition = matchOrNone(remote?.etag)
                    )
                }
                // Both sides already gone: only the marker needs to travel.
                MergeDecision.NoChange -> tombstones += id
                // One side deleted, the other edited: the edit wins, and is reviewed.
                is MergeDecision.DeleteVsEdit -> {
                    val forced = forcedBy(id, resolutions[id], local, remote?.fields, remote?.etag, remoteAbsent = remote == null)
                    if (forced != null) {
                        // Same rule as the merge section below: an answer the user already gave is
                        // not asked again. Checked here too because this is a second place a
                        // delete-versus-edit gets parked, and a hole here would leave an answer
                        // stored forever without ever being honoured.
                        items += forced
                        appliedResolutions += id
                    } else {
                        if (decision.winner == Side.Local) {
                            items += SyncItem(
                                id, decision, SyncVerb.PUSH,
                                localFields = local?.fields, precondition = PutPrecondition.MustNotExist
                            )
                        } else {
                            items += SyncItem(
                                id, decision, SyncVerb.PULL,
                                remoteFields = remote?.fields, remoteEtag = remote?.etag
                            )
                        }
                        reviews += ConflictReview(id, DELETE_VS_EDIT_NOTE, decision)
                    }
                }
                // Should not arise for a confirmed-absence candidate, but if the states are
                // stranger than expected, refuse to move data and surface it.
                else -> {
                    items += SyncItem(id, decision, SyncVerb.NONE)
                    reviews += ConflictReview(id, CONFLICT_NOTE, decision)
                }
            }
        }

        // ---- 2. Absences seen but not yet confirmed: watch, do not act --------
        for ((token, since) in deletions.pending) {
            val id = SyncIdentity.fromToken(token) ?: continue
            handled += id
            pending[id] = since
        }

        // ---- 3. The merge ----------------------------------------------------
        for (id in (localById.keys + remoteById.keys) - handled) {
            val base = baseById[id]
            val local = localById[id]
            val remote = remoteById[id]
            val remoteFields = remote?.fields

            if (remote != null && remoteFields == null) {
                // Present but unreadable: never let it read as absent, and never act on it.
                val forced = forcedBy(id, resolutions[id], local, remoteFields, remote.etag, remoteAbsent = false)
                if (forced != null) {
                    // A stored override can still be honoured here — but only "keep this
                    // device's version". Adopting a payload we could not read is not an
                    // option any side has, so a "keep the peer's" answer stays parked.
                    items += forced
                    appliedResolutions += id
                } else {
                    items += SyncItem(id, MergeDecision.NoChange, SyncVerb.NONE)
                    reviews += ConflictReview(id, UNREADABLE_NOTE)
                }
                continue
            }

            val decision = ThreeWayMerge.decide(
                type = id.recordType,
                key = id.key,
                base = base?.let { toState(it) },
                local = local,
                remote = if (remote != null) RecordState(id.recordType, id.key, remoteFields ?: emptyMap()) else null
            )

            when (decision) {
                MergeDecision.NoChange -> items += SyncItem(
                    id, decision, SyncVerb.NONE,
                    remoteFields = remoteFields, remoteEtag = remote?.etag
                )

                MergeDecision.KeepLocal -> items += SyncItem(
                    id, decision, SyncVerb.PUSH,
                    localFields = local?.fields, remoteEtag = remote?.etag,
                    precondition = pushPrecondition(base, remote)
                )

                MergeDecision.TakeRemote -> items += SyncItem(
                    id, decision, SyncVerb.PULL,
                    remoteFields = remoteFields, remoteEtag = remote?.etag
                )

                // Unreachable for a key that is present on either side and not a deletion
                // candidate, but kept for totality.
                MergeDecision.DeleteLocal -> items += SyncItem(id, decision, SyncVerb.DELETE_LOCAL)

                MergeDecision.DeleteRemote -> items += SyncItem(
                    id, decision, SyncVerb.DELETE_REMOTE,
                    remoteEtag = remote?.etag, precondition = matchOrNone(remote?.etag)
                )

                is MergeDecision.Conflict -> {
                    val forced = forcedBy(id, resolutions[id], local, remoteFields, remote?.etag, remoteAbsent = remote == null)
                    if (forced != null) {
                        // The user already answered this one; asking again would be the bug.
                        items += forced
                        appliedResolutions += id
                    } else {
                        items += SyncItem(id, decision, SyncVerb.NONE)
                        reviews += ConflictReview(id, CONFLICT_NOTE, decision)
                    }
                }

                is MergeDecision.DeleteVsEdit -> {
                    val forced = forcedBy(id, resolutions[id], local, remoteFields, remote?.etag, remoteAbsent = remote == null)
                    if (forced != null) {
                        items += forced
                        appliedResolutions += id
                    } else {
                        if (decision.winner == Side.Local) {
                            items += SyncItem(
                                id, decision, SyncVerb.PUSH,
                                localFields = local?.fields, precondition = PutPrecondition.MustNotExist
                            )
                        } else {
                            items += SyncItem(
                                id, decision, SyncVerb.PULL,
                                remoteFields = remoteFields, remoteEtag = remote?.etag
                            )
                        }
                        reviews += ConflictReview(id, DELETE_VS_EDIT_NOTE, decision)
                    }
                }
            }
        }

        // A forced `DELETE_LOCAL` (the user accepted the peer's *deletion*) is a deletion like any
        // other, so it gets the same evidence a rule-decided one does. Collected here rather than
        // at each forced call site so the two places an answer is honoured cannot drift apart.
        for (id in appliedResolutions) {
            if (items.any { it.identity == id && it.verb == SyncVerb.DELETE_LOCAL }) tombstones += id
        }

        // A stored override is consumed in one of two ways, and the difference is the point:
        //  * it became an action this round (appliedResolutions) — cleared once that action
        //    lands, so a rejected write can be retried with the choice intact;
        //  * the record it named is no longer parked — nothing is left to resolve, so the
        //    override is stale and is cleared outright.
        // Only identities this round actually looked at can be judged either way: an override
        // for a record type the round does not carry must survive untouched, or a round that
        // syncs only accounts would silently drop a transaction resolution.
        val examined = localById.keys + remoteById.keys + baseById.keys
        val parked = reviews.mapTo(mutableSetOf()) { it.identity }
        // `decided` is what separates "the conflict went away" from "the round did not get to this
        // record yet". A first-seen absence is merely *watched* and produces no item, so an answer
        // for it must be **kept**: the two-round promotion may park exactly that record next
        // round, and the user's choice is already the right answer for that situation.
        val decided = items.mapTo(mutableSetOf()) { it.identity }
        val droppedResolutions = resolutions.keys
            .filter { it in examined && it !in parked && it !in appliedResolutions && it in decided }
            .toSet()

        return SyncPlan(
            items = items.sortedBy { it.identity.token },
            reviews = reviews.sortedBy { it.identity.token },
            tombstones = tombstones,
            pending = pending.entries.sortedBy { it.key.token }.associateTo(LinkedHashMap()) { it.toPair() },
            abandoned = false,
            resolutionsApplied = appliedResolutions,
            resolutionsDropped = droppedResolutions
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun abandon(bases: Map<SyncIdentity, SyncBase>): SyncPlan = SyncPlan(
        items = emptyList(),
        reviews = emptyList(),
        tombstones = emptySet(),
        pending = pendingOf(bases),
        abandoned = true
    )

    /** The absences already being watched, taken from the base rows' own markers. */
    private fun pendingOf(bases: Map<SyncIdentity, SyncBase>): Map<SyncIdentity, Long> =
        bases.mapNotNull { (id, base) -> base.pendingSince?.let { id to it } }.toMap()

    /**
     * The base as a record state, or `null` when its payload cannot be decoded.
     *
     * An unreadable base must degrade to "no common ancestor", not to "an ancestor
     * with no fields": the former turns the record into a reviewable conflict, the
     * latter would look like the peer deleted every field.
     */
    private fun toState(base: SyncBase): RecordState? =
        RecordCodec.decodeOrNull(base.payload)?.let {
            RecordState(base.recordType, base.recordKey, it)
        }

    /**
     * The precondition for a `PUT` that pushes the local record out.
     *
     * A record the base has never seen is a **create** and must prove the slot is
     * still empty (`If-None-Match: *`); anything else is an **update** and must prove
     * the peer is still the version we merged against (`If-Match`). `None` is only
     * produced when there is genuinely no etag to compare — an un-seen create with an
     * unknown peer etag never happens, because a create always uses `MustNotExist`.
     */
    private fun pushPrecondition(base: SyncBase?, remote: RemoteState?): PutPrecondition {
        if (base == null) return PutPrecondition.MustNotExist
        val etag = remote?.etag ?: base.etag
        return etag?.let { PutPrecondition.MustMatch(it) } ?: PutPrecondition.None
    }

    private fun matchOrNone(etag: String?): PutPrecondition =
        etag?.let { PutPrecondition.MustMatch(it) } ?: PutPrecondition.None

    /**
     * The forced action a stored [ResolveSide] implies, or `null` when there is nothing to
     * force (feature ② v3).
     *
     * The mapping is *state*-based, not verb-based, and that is the whole subtlety: "keep
     * this device's version" is a `PUSH` when this device has the record and a
     * `DELETE_REMOTE` when it does not (we deleted it here, the peer edited it). Reading the
     * user's answer as "push" instead would resurrect a record they deliberately deleted.
     *
     * `null` is returned only when the chosen side has nothing to keep: "keep the peer's
     * version" against a record whose peer copy is present but **unreadable** (there is no
     * content to adopt). The record then stays parked and the override stays stored, waiting
     * for a round where the peer *is* readable.
     *
     * The callers only reach this at the points where a record would otherwise be **parked**,
     * which is also what guarantees at least one side has the record — so neither branch below
     * has to cope with "both sides absent" (a state that is never parked).
     *
     * @param remoteAbsent `true` when the peer has **no copy** of the record (a confirmed
     *        deletion), as opposed to a copy that merely could not be read.
     */
    private fun forcedBy(
        id: SyncIdentity,
        side: ResolveSide?,
        local: RecordState?,
        remoteFields: Map<String, String>?,
        remoteEtag: String?,
        remoteAbsent: Boolean
    ): SyncItem? = when (side) {
        null -> null
        ResolveSide.LOCAL -> if (local != null) {
            SyncItem(
                id, MergeDecision.KeepLocal, SyncVerb.PUSH,
                localFields = local.fields, remoteEtag = remoteEtag,
                precondition = forcedPushPrecondition(!remoteAbsent, remoteEtag)
            )
        } else {
            SyncItem(
                id, MergeDecision.DeleteRemote, SyncVerb.DELETE_REMOTE,
                remoteEtag = remoteEtag, precondition = matchOrNone(remoteEtag)
            )
        }
        ResolveSide.REMOTE -> when {
            // The peer's version *is* its absence: accepting it means deleting the copy here.
            remoteAbsent -> SyncItem(id, MergeDecision.DeleteLocal, SyncVerb.DELETE_LOCAL)
            remoteFields != null -> SyncItem(
                id, MergeDecision.TakeRemote, SyncVerb.PULL,
                remoteFields = remoteFields, remoteEtag = remoteEtag
            )
            else -> null
        }
    }

    /**
     * The precondition for a resolution-forced `PUT`.
     *
     * Deliberately **not** [pushPrecondition]: a forced push is not merging anything, so when
     * the peer holds no copy the write is a **create** (`If-None-Match: *`) even if a base row
     * exists. That happens whenever the user chose "keep this device's version" over a peer
     * *deletion*, and using an `If-Match` against an etag the peer has already dropped would
     * make the write fail the same way every round — an override the user can never land.
     */
    private fun forcedPushPrecondition(remotePresent: Boolean, remoteEtag: String?): PutPrecondition =
        when {
            !remotePresent -> PutPrecondition.MustNotExist
            remoteEtag != null -> PutPrecondition.MustMatch(remoteEtag)
            else -> PutPrecondition.None
        }
}
