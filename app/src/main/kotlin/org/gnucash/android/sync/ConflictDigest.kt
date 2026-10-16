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

/**
 * What kind of situation one parked record is in — the conflict centre's **list** row.
 *
 * Derived from the decision structure, never from [ConflictReview.note]. The note is
 * free-form English written for the log; reading it to decide what to show would make the
 * UI depend on the wording of a log line, and a reworded note would silently reclassify
 * every row (plan §7.2).
 */
enum class ConflictKind {
    /** Both sides edited the same record differently: neither change can be dropped. */
    BOTH_EDITED,

    /** Deleted on the peer, edited here. The edit survives; the user is told why. */
    DELETED_THERE_EDITED_HERE,

    /** Deleted here, edited on the peer. The edit survives; the user is told why. */
    DELETED_HERE_EDITED_THERE,

    /**
     * The peer's file is present but could not be read, so there was nothing to compare.
     *
     * Deliberately its own kind: rendering it as "0 differences" would read as "the two
     * sides agree", which is the one conclusion that must never be drawn from an
     * unreadable payload (see [RemoteState.fields]).
     */
    UNREADABLE
}

/**
 * What happened to **one field** between the three states.
 *
 * The vocabulary exists so the UI can say *which side moved*, rather than only showing
 * two columns and leaving the user to work it out. Which of these applies is decided
 * once, here, from `(base, local, remote)` — see [ConflictDigest.deltaKindOf].
 */
enum class FieldDeltaKind {
    /** The peer left it alone; this side changed it. */
    LOCAL_CHANGED,

    /** This side left it alone; the peer changed it. */
    REMOTE_CHANGED,

    /** Both sides changed it, to different values. The genuine conflict case. */
    BOTH_CHANGED,

    /** No common ancestor had it; only this side does. */
    ONLY_LOCAL,

    /** No common ancestor had it; only the peer does. */
    ONLY_REMOTE,

    /** The ancestor had it, this side dropped it. */
    LOCAL_DROPPED,

    /** The ancestor had it, the peer dropped it. */
    REMOTE_DROPPED,

    /**
     * The ancestor had it; **neither** side has it now.
     *
     * Reachable through [MergeDecision.DeleteVsEdit], whose diff is computed against a
     * survivor whose own field set may have shrunk: the surviving side and the deleted
     * side can both be without a field the ancestor carried.
     */
    BOTH_DROPPED
}

/**
 * One record as the conflict centre renders it: the identity, the situation, and the
 * field-level differences to show underneath.
 *
 * @param diffs the field differences behind [kind]. Empty for [ConflictKind.UNREADABLE]
 *        (there was nothing to compare) and possible-but-unusual for the other kinds —
 *        [ConflictDigest] never invents a diff to fill the gap, so the UI must be able to
 *        say "no comparable field differences" rather than showing an empty table.
 */
data class ConflictRow(
    val identity: SyncIdentity,
    val kind: ConflictKind,
    val diffs: List<FieldDiff>
)

/**
 * The field differences behind a reviewable decision, or an empty list for anything else.
 *
 * [MergeDecision.Conflict] and [MergeDecision.DeleteVsEdit] already carry their own
 * [FieldDiff] lists; the other decisions are never parked for review, so they have none.
 */
val MergeDecision.fieldDiffs: List<FieldDiff>
    get() = when (this) {
        is MergeDecision.Conflict -> diffs
        is MergeDecision.DeleteVsEdit -> diffs
        else -> emptyList()
    }

/**
 * Turns the round's parked [ConflictReview]s into the rows the conflict centre shows.
 *
 * Pure: no Android, no database, no wording. Everything the UI needs to *decide* what to
 * display is computed here, so the classification can be asserted exhaustively without a
 * device — and so the UI is left with nothing but lookups and string formatting.
 */
object ConflictDigest {

    /**
     * The situation behind a decision.
     *
     * A `null` decision is [ConflictKind.UNREADABLE]: [ConflictReview.decision] is only
     * absent for a record that was parked precisely *because* it could not be read.
     *
     * Any other decision kind is unreachable — the planner parks only conflicts, deletes
     * versus edits and unreadable records — and is reported as [ConflictKind.UNREADABLE]
     * rather than being folded into "both edited", so that a future planner that parks
     * something new shows up as "cannot show you this" instead of a plausible wrong story.
     */
    fun kindOf(decision: MergeDecision?): ConflictKind = when (decision) {
        null -> ConflictKind.UNREADABLE
        is MergeDecision.Conflict -> ConflictKind.BOTH_EDITED
        is MergeDecision.DeleteVsEdit -> when (decision.winner) {
            Side.Local -> ConflictKind.DELETED_THERE_EDITED_HERE
            Side.Remote -> ConflictKind.DELETED_HERE_EDITED_THERE
        }
        else -> ConflictKind.UNREADABLE
    }

    /**
     * What one [FieldDiff] means, from its three values.
     *
     * The cases are exhaustive over what [ThreeWayMerge] can emit, so there is no
     * "unknown" bucket: a diff with all three values `null` is **not** covered here
     * because [ThreeWayMerge.diffFields] and [ThreeWayMerge.editDiff] both drop fields the
     * two sides agree on — but if one ever did appear, `base == null` with neither side
     * set would be reported as [FieldDeltaKind.BOTH_CHANGED] rather than crashing, since
     * the caller's only sane next move (show all three columns, let the user judge) is the
     * same.
     */
    fun deltaKindOf(diff: FieldDiff): FieldDeltaKind {
        val base = diff.base
        val local = diff.local
        val remote = diff.remote
        return when {
            // Reachable only via a DeleteVsEdit diff: the surviving side dropped a field
            // the ancestor had, and the other side is the one that was deleted.
            local == null && remote == null -> FieldDeltaKind.BOTH_DROPPED
            base == null -> when {
                local != null && remote != null -> FieldDeltaKind.BOTH_CHANGED
                local != null -> FieldDeltaKind.ONLY_LOCAL
                else -> FieldDeltaKind.ONLY_REMOTE
            }
            local == null -> FieldDeltaKind.LOCAL_DROPPED
            remote == null -> FieldDeltaKind.REMOTE_DROPPED
            base == local -> FieldDeltaKind.REMOTE_CHANGED
            base == remote -> FieldDeltaKind.LOCAL_CHANGED
            else -> FieldDeltaKind.BOTH_CHANGED
        }
    }

    /**
     * Builds the rows, ordered the same way the round ordered its reviews (`identity.token`).
     *
     * Ordering is re-applied here rather than assumed: the rows are what the user reads
     * top to bottom, and a list whose order depends on which round produced it would make
     * two identical situations look different.
     */
    fun of(reviews: List<ConflictReview>): List<ConflictRow> =
        reviews
            .map { ConflictRow(it.identity, kindOf(it.decision), it.decision?.fieldDiffs.orEmpty()) }
            .sortedBy { it.identity.token }
}
