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
 * A record's field values on **one** side of a merge.
 *
 * A missing record is represented by `null` at the call site ([decide] takes
 * nullable states), never by an empty map — "absent" and "present but blank" are
 * different facts and conflating them is exactly how a deletion gets mistaken for
 * an edit.
 */
data class RecordState(
    val type: SyncRecordType,
    val key: String,
    val fields: Map<String, String>
)

/** Which side a decision favours. */
enum class Side { Local, Remote }

/**
 * One field where the two sides disagree. `null` means "that side has no value
 * for this field" (either absent record or missing key), which is what the
 * conflict centre shows as a blank cell (plan §7.2).
 */
data class FieldDiff(
    val field: String,
    val base: String?,
    val local: String?,
    val remote: String?
)

/**
 * What to do with a single record after comparing `local / remote / base`.
 *
 * The decision vocabulary is intentionally tiny and side-effect free: this class
 * decides, the caller applies. That keeps the risky part (plan risk R-1,
 * "log-green, transactions-missing") unit-testable without a network or a device.
 */
sealed interface MergeDecision {
    /** Nothing to do: the sides agree (or nothing exists on either side). */
    data object NoChange : MergeDecision

    /** Local is the (or the only surviving) change — keep it and push it out. */
    data object KeepLocal : MergeDecision

    /** Remote is the (or the only surviving) change — adopt it locally. */
    data object TakeRemote : MergeDecision

    /** Remote deleted it and local has no competing edit — delete locally too. */
    data object DeleteLocal : MergeDecision

    /** Local deleted it and remote has no competing edit — delete remotely too. */
    data object DeleteRemote : MergeDecision

    /** Both sides changed the same record differently — the conflict centre decides. */
    data class Conflict(val diffs: List<FieldDiff>) : MergeDecision

    /**
     * One side deleted, the other edited. The edit is adopted (a modification is
     * recoverable, a delete is not) but the situation is still surfaced for review,
     * per plan §7.2.
     */
    data class DeleteVsEdit(val winner: Side, val diffs: List<FieldDiff>) : MergeDecision
}

/** `true` when the user should see this record in the conflict centre. */
val MergeDecision.needsConflictReview: Boolean
    get() = when (this) {
        is MergeDecision.Conflict, is MergeDecision.DeleteVsEdit -> true
        else -> false
    }

/**
 * The **pure** three-way merge core (plan §7.2 / §7.4).
 *
 * Everything here is a function of its arguments: no database, no clock, no
 * network. That is deliberate — this is the component where a wrong answer means
 * irreversibly lost transactions, so it must be exhaustively assertable.
 */
object ThreeWayMerge {

    /** The record was created on both sides with different content. */
    private const val CREATED_ON_BOTH_SIDES = "created on both sides"

    /**
     * Decides what to do with one record.
     *
     * @param type   the kind of record (used only for the returned [FieldDiff] context).
     * @param key    the record identity (GnuCash GUID).
     * @param base   state as of the last successful sync; `null` when the base had
     *               no such record (never synced, or created since).
     * @param local  state on this device; `null` when deleted here (or never existed here).
     * @param remote state on the peer; `null` when deleted there (or never existed there).
     */
    fun decide(
        type: SyncRecordType,
        key: String,
        base: RecordState?,
        local: RecordState?,
        remote: RecordState?
    ): MergeDecision {
        // Both sides gone — nothing left to reconcile.
        if (local == null && remote == null) return MergeDecision.NoChange

        if (local == null) {
            // Local is absent: either it never had the record, or it deleted it.
            if (base == null) return MergeDecision.TakeRemote      // remote created it
            return if (fieldsEqual(remote!!.fields, base.fields)) {
                MergeDecision.DeleteRemote                          // remote untouched ⇒ delete is the change
            } else {
                MergeDecision.DeleteVsEdit(Side.Remote, editDiff(base, remote, Side.Remote))
            }
        }

        if (remote == null) {
            if (base == null) return MergeDecision.KeepLocal        // local created it
            return if (fieldsEqual(local.fields, base.fields)) {
                MergeDecision.DeleteLocal                           // local untouched ⇒ adopt the delete
            } else {
                MergeDecision.DeleteVsEdit(Side.Local, editDiff(base, local, Side.Local))
            }
        }

        // Both present.
        if (fieldsEqual(local.fields, remote.fields)) return MergeDecision.NoChange

        if (base == null) {
            // Neither side had a base for this key ⇒ both created it, differently.
            return MergeDecision.Conflict(describeBothCreated(type, key, local, remote))
        }

        val localChanged = !fieldsEqual(local.fields, base.fields)
        val remoteChanged = !fieldsEqual(remote.fields, base.fields)
        return when {
            localChanged && !remoteChanged -> MergeDecision.KeepLocal
            !localChanged && remoteChanged -> MergeDecision.TakeRemote
            else -> MergeDecision.Conflict(diffFields(base, local, remote))
        }
    }

    /**
     * Per-field differences between the two sides. Only fields where `local` and
     * `remote` actually disagree are reported — a field both sides changed *the
     * same way* is not a conflict.
     */
    fun diffFields(base: RecordState?, local: RecordState?, remote: RecordState?): List<FieldDiff> {
        val fields = sortedSetOf<String>()
        fields += base?.fields?.keys.orEmpty()
        fields += local?.fields?.keys.orEmpty()
        fields += remote?.fields?.keys.orEmpty()
        return fields.mapNotNull { field ->
            val l = local?.fields?.get(field)
            val r = remote?.fields?.get(field)
            if (l == r) null else FieldDiff(field, base?.fields?.get(field), l, r)
        }
    }

    /**
     * Records a deletion **only** when the absence is confirmed (plan §7.3,
     * constraint 2) and after a second consecutive absence (two-round promotion).
     *
     * @param watched          keys we already knew about, i.e. `base ∪ pending`. The
     *                         previous `pending` keys are unioned in here **on purpose**:
     *                         a key that was absent last round only survives in
     *                         `previousPending`, so iterating `watched` alone would mean
     *                         it can never reach its second confirmation and a real
     *                         deletion would never converge.
     * @param present          keys confirmed present this round.
     * @param previousPending  key → timestamp of its first confirmed absence.
     * @param now              timestamp for entries first seen absent this round (epoch millis).
     * @param listingComplete  `true` only when the listing ran to completion. A truncated
     *                         or failed listing is **not** evidence of anything, so the
     *                         round is abandoned with no writes at all.
     * @param locallyDeleted   keys deleted on this device this round. That is the only
     *                         *self-evident* deletion, so it is tombstoned immediately
     *                         instead of waiting for a second round.
     */
    fun planDeletions(
        watched: Set<String>,
        present: Set<String>,
        previousPending: Map<String, Long>,
        now: Long,
        listingComplete: Boolean,
        locallyDeleted: Set<String> = emptySet()
    ): DeletionPlan {
        // Gate 1: an incomplete listing proves nothing. Write nothing at all —
        // not even a pending entry — so a network hiccup can never become a delete.
        if (!listingComplete) {
            return DeletionPlan(tombstones = emptySet(), pending = previousPending, abandoned = true)
        }

        val effectiveWatched = watched + previousPending.keys

        // Gate 2: a listing that came back empty while we knew about records is
        // not trusted either. The accepted cost is that deleting the last record
        // does not synchronise; that is far cheaper than randomly deleting
        // everything (and deleting something ourselves this round lifts the gate).
        if (present.isEmpty() && locallyDeleted.isEmpty() && effectiveWatched.isNotEmpty()) {
            return DeletionPlan(tombstones = emptySet(), pending = previousPending, abandoned = true)
        }

        val tombstones = mutableSetOf<String>()
        val pending = mutableMapOf<String, Long>()

        for (key in effectiveWatched) {
            if (key in present) continue                       // came back ⇒ pending clears
            if (key in locallyDeleted) {
                tombstones += key                              // our own delete: one round is enough
                continue
            }
            when {
                previousPending.containsKey(key) -> tombstones += key   // second absence ⇒ promote
                else -> pending[key] = now                              // first absence ⇒ watch it
            }
        }

        // A locally deleted key may not have been in `watched` (e.g. created and
        // deleted between two syncs). It still needs a marker to travel.
        for (key in locallyDeleted) {
            if (key !in present) tombstones += key
        }

        return DeletionPlan(tombstones = tombstones, pending = pending, abandoned = false)
    }

    /**
     * The fields a surviving side actually **changed** relative to the base, for a
     * delete-vs-edit decision.
     *
     * Deliberately not the same question as [diffFields]: when one side deleted the
     * record it has no per-field values at all, so comparing "survivor vs absent"
     * would flag *every* field as divergent — including fields nobody touched. The
     * edit is what differs from the base; the deleted side is reported as `null`
     * (a blank cell in the conflict centre), never as an empty string.
     */
    private fun editDiff(base: RecordState, survivor: RecordState, survivorSide: Side): List<FieldDiff> {
        val fields = sortedSetOf<String>()
        fields += base.fields.keys
        fields += survivor.fields.keys
        return fields.mapNotNull { field ->
            val before = base.fields[field]
            val after = survivor.fields[field]
            if (before == after) null else when (survivorSide) {
                Side.Remote -> FieldDiff(field, before, null, after)
                Side.Local -> FieldDiff(field, before, after, null)
            }
        }
    }

    private fun describeBothCreated(
        type: SyncRecordType,
        key: String,
        local: RecordState,
        remote: RecordState
    ): List<FieldDiff> {
        val diffs = diffFields(null, local, remote)
        // Always non-empty here (the sides differ), but guard so the conflict list
        // can never be silently empty for a Conflict decision.
        return diffs.ifEmpty {
            listOf(FieldDiff(CREATED_ON_BOTH_SIDES, null, "${type.id}:$key", "${type.id}:$key"))
        }
    }

    private fun fieldsEqual(a: Map<String, String>, b: Map<String, String>): Boolean = a == b
}

/**
 * The outcome of one deletion-planning round.
 *
 * @param tombstones keys that are now **confirmed** deleted and must be propagated.
 * @param pending    keys whose absence is still unconfirmed (first sighting), carried
 *                   into the next round. A key that came back present is dropped.
 * @param abandoned  `true` when the round was refused wholesale; callers must then
 *                   leave every local record untouched.
 */
data class DeletionPlan(
    val tombstones: Set<String>,
    val pending: Map<String, Long>,
    val abandoned: Boolean
)
