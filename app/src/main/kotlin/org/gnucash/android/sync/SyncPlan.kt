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
 * What the round knows about one record on the **peer**, after the listing (and,
 * where needed, a fetch).
 *
 * @param fields the decoded field map, or `null` when the record is present but
 *        could not be read (undecodable payload, or the fetch failed). `null` is
 *        deliberately distinct from an empty map: "the peer has a record we cannot
 *        read" must never be mistaken for "the peer has a record with no fields",
 *        and it must be impossible to conclude a deletion from it — the record **is**
 *        present, it is merely unreadable, so the safe action is to leave it alone.
 */
data class RemoteState(
    val identity: SyncIdentity,
    val etag: String?,
    val fields: Map<String, String>?,
    val path: String
)

/**
 * The single action a round takes for one record.
 *
 * One verb per record, chosen by [ThreeWayMerge.decide]; the vocabulary mirrors
 * the merge decisions so the mapping is checkable at a glance.
 */
enum class SyncVerb {
    /** Nothing to do (the sides agree, or the record is only under review). */
    NONE,

    /** Push the local record to the peer (`PUT`, always under a precondition). */
    PUSH,

    /** Adopt the remote record locally (the payload has already been fetched). */
    PULL,

    /** Delete the record on the peer (it is gone here). */
    DELETE_REMOTE,

    /** Delete the record here (it is gone on the peer, confirmed). */
    DELETE_LOCAL
}

/**
 * One record's plan for this round.
 *
 * @param localFields  the fields to `PUT`, for [SyncVerb.PUSH].
 * @param remoteFields the fields to adopt, or to record as the new base, for
 *                     [SyncVerb.PULL] and [SyncVerb.NONE].
 * @param remoteEtag   the peer's entity tag as of this round's listing. Used both
 *                     as the `If-Match` value for conditional writes and as the
 *                     etag recorded in the new base.
 * @param precondition what the write must prove; see [PutPrecondition]. Chosen by
 *                     the planner, never by the caller, so a `PUT` cannot reach the
 *                     wire without one.
 */
data class SyncItem(
    val identity: SyncIdentity,
    val decision: MergeDecision,
    val verb: SyncVerb,
    val localFields: Map<String, String>? = null,
    val remoteFields: Map<String, String>? = null,
    val remoteEtag: String? = null,
    val precondition: PutPrecondition = PutPrecondition.None
)

/**
 * A record the user should see in the conflict centre (plan §7.2).
 *
 * @param decision the merge decision behind the review, when there is one; `null`
 *        for reviews that are not merge conflicts (e.g. an unreadable remote file).
 * @param note     a short, human-readable explanation shown alongside the diff.
 */
data class ConflictReview(
    val identity: SyncIdentity,
    val note: String,
    val decision: MergeDecision? = null
)

/**
 * One round's decisions — the output of [SyncRoundPlanner], the input of the
 * applier.
 *
 * Pure data on purpose: the planner produces it with no side effects, and the
 * executor consumes it. That split is what lets "which record does what" be
 * asserted exhaustively without a network, a database, or a clock.
 *
 * @param items      one entry per record the round acts on (or deliberately leaves
 *                   alone). Ordered deterministically for reproducible logs/tests.
 * @param reviews    records the user must see, whether or not an action was taken.
 * @param tombstones identities whose deletion is now **confirmed** and must be
 *                   recorded; the executor writes these to the tombstone table.
 * @param pending    identities whose absence is seen but not yet confirmed, mapped to
 *                   the millis at which the absence was first seen. Carried into the
 *                   next round; a record that reappears is dropped from this map.
 * @param abandoned  `true` when the round refused to decide anything (an incomplete
 *                   listing, or an empty listing while records are known). The only
 *                   correct response is to touch **nothing** — no items, no
 *                   tombstones, and `pending` unchanged from the previous round.
 * @param resolutionsApplied identities whose stored [ResolveSide] was turned into a
 *                   forced action this round (feature ② v3). The executor clears the
 *                   override **once that action has landed**, and deliberately not
 *                   before: a push rejected with `412` or a transport failure must leave
 *                   the user's choice in place so the next round tries again, rather than
 *                   silently dropping it and re-parking a conflict the user already
 *                   settled.
 * @param resolutionsDropped identities whose stored [ResolveSide] is **moot** — a record the
 *                   round decided by rule instead of parking (the two sides stopped
 *                   disagreeing, or only one side moved). Reported separately from
 *                   [resolutionsApplied] because there is no action whose success could gate
 *                   it, so the executor clears these unconditionally. Only identities the
 *                   round actually **looked at and decided** appear here: an override for a
 *                   record type the round does not carry, or for a record it merely put
 *                   under observation (a first-seen absence), must survive untouched — the
 *                   latter may well be parked next round, and the answer is already the
 *                   right one for exactly that situation.
 */
data class SyncPlan(
    val items: List<SyncItem>,
    val reviews: List<ConflictReview>,
    val tombstones: Set<SyncIdentity>,
    val pending: Map<SyncIdentity, Long>,
    val abandoned: Boolean,
    val resolutionsApplied: Set<SyncIdentity> = emptySet(),
    val resolutionsDropped: Set<SyncIdentity> = emptySet()
) {
    val uploads: List<SyncItem> get() = items.filter { it.verb == SyncVerb.PUSH }
    val downloads: List<SyncItem> get() = items.filter { it.verb == SyncVerb.PULL }
    val remoteDeletes: List<SyncItem> get() = items.filter { it.verb == SyncVerb.DELETE_REMOTE }
    val localDeletes: List<SyncItem> get() = items.filter { it.verb == SyncVerb.DELETE_LOCAL }
    val agreements: List<SyncItem> get() = items.filter { it.verb == SyncVerb.NONE }
}

/**
 * Everything [SyncRoundPlanner.plan] needs, and nothing it does not.
 *
 * @param local  the records currently on this device.
 * @param remote the records the peer listed that are ours (already fetched where the
 *               etag said the content might have changed).
 * @param bases  the previous successful sync's snapshots — the common ancestor.
 * @param now    epoch millis, used to timestamp first-seen absences.
 * @param listingComplete whether the listing ran to completion. `false` makes the
 *        planner abandon the round wholesale; see [SyncPlan.abandoned].
 * @param resolutions the stored "keep this side" overrides from the conflict centre
 *        (feature ② v3), keyed by record. Defaults to empty so every existing caller —
 *        and every test that predates the feature — keeps the previous behaviour exactly.
 *        A record with an override is not parked again: the override *is* the answer, and
 *        re-parking it would ask the user the same question forever.
 */
data class PlanInput(
    val local: List<RecordState>,
    val remote: List<RemoteState>,
    val bases: List<SyncBase>,
    val now: Long,
    val listingComplete: Boolean,
    val resolutions: Map<SyncIdentity, ResolveSide> = emptyMap()
)
