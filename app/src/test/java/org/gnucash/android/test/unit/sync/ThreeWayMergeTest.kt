package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.DeletionPlan
import org.gnucash.android.sync.FieldDiff
import org.gnucash.android.sync.MergeDecision
import org.gnucash.android.sync.RecordState
import org.gnucash.android.sync.Side
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.ThreeWayMerge
import org.gnucash.android.sync.needsConflictReview
import org.junit.Test

/**
 * Exhaustive cases for the pure three-way merge core (plan §7.2 / §7.4).
 *
 * Deliberately a plain JVM test: the core takes no Android dependency, so the
 * decisions that guard against irreversibly losing transactions can be listed
 * case by case instead of being buried behind a database.
 */
class ThreeWayMergeTest {

    // ---------------------------------------------------------------- helpers

    private fun tx(key: String, description: String, notes: String = "") = RecordState(
        type = SyncRecordType.TRANSACTION,
        key = key,
        fields = mapOf("description" to description, "notes" to notes)
    )

    private fun decide(
        base: RecordState?,
        local: RecordState?,
        remote: RecordState?,
        key: String = "T1"
    ): MergeDecision = ThreeWayMerge.decide(SyncRecordType.TRANSACTION, key, base, local, remote)

    // ------------------------------------------------- §7.2: both sides present

    @Test
    fun `identical on both sides and with the base needs no change`() {
        val a = tx("T1", "Lunch")
        assertThat(decide(base = a, local = a, remote = a)).isEqualTo(MergeDecision.NoChange)
    }

    @Test
    fun `both sides changed the same way is not a conflict`() {
        val base = tx("T1", "Lunch")
        val both = tx("T1", "Dinner")
        assertThat(decide(base = base, local = both, remote = both)).isEqualTo(MergeDecision.NoChange)
    }

    @Test
    fun `only the local side changed keeps the local version`() {
        val base = tx("T1", "Lunch")
        val local = tx("T1", "Dinner")
        assertThat(decide(base = base, local = local, remote = base)).isEqualTo(MergeDecision.KeepLocal)
    }

    @Test
    fun `only the remote side changed adopts the remote version`() {
        val base = tx("T1", "Lunch")
        val remote = tx("T1", "Dinner")
        assertThat(decide(base = base, local = base, remote = remote)).isEqualTo(MergeDecision.TakeRemote)
    }

    @Test
    fun `three different versions is a conflict listing only the divergent fields`() {
        val base = tx("T1", "Lunch", "a")
        val local = tx("T1", "Dinner", "a")
        val remote = tx("T1", "Lunch", "b")

        val decision = decide(base = base, local = local, remote = remote)
        assertThat(decision).isInstanceOf(MergeDecision.Conflict::class.java)
        assertThat((decision as MergeDecision.Conflict).diffs.map { it.field })
            .containsExactly("description", "notes")
        assertThat(decision.needsConflictReview).isTrue()
    }

    @Test
    fun `the same field changed to the same value is not reported as a difference`() {
        val base = tx("T1", "Lunch", "a")
        val local = tx("T1", "Dinner", "a")
        val remote = tx("T1", "Lunch", "b")

        val diffs = ThreeWayMerge.diffFields(base, local, remote)
        // "value unchanged on both sides" must never show up as a diff cell; here
        // every field diverges, so the point is the *shape* of each diff.
        assertThat(diffs).hasSize(2)
        assertThat(diffs.first { it.field == "description" })
            .isEqualTo(FieldDiff("description", "Lunch", "Dinner", "Lunch"))
    }

    @Test
    fun `a field both sides changed identically is not a diff`() {
        val base = RecordState(SyncRecordType.TRANSACTION, "T1", mapOf("a" to "1", "b" to "1"))
        val local = RecordState(SyncRecordType.TRANSACTION, "T1", mapOf("a" to "2", "b" to "1"))
        val remote = RecordState(SyncRecordType.TRANSACTION, "T1", mapOf("a" to "2", "b" to "1"))
        assertThat(ThreeWayMerge.diffFields(base, local, remote)).isEmpty()
    }

    // ------------------------------------------------- §7.2: creation on one side

    @Test
    fun `a record that exists only remotely is created locally`() {
        val remote = tx("T1", "Lunch")
        assertThat(decide(base = null, local = null, remote = remote)).isEqualTo(MergeDecision.TakeRemote)
    }

    @Test
    fun `a record that exists only locally is kept`() {
        val local = tx("T1", "Lunch")
        assertThat(decide(base = null, local = local, remote = null)).isEqualTo(MergeDecision.KeepLocal)
    }

    @Test
    fun `a record created on both sides with different content is a conflict`() {
        val decision = decide(base = null, local = tx("T1", "Lunch"), remote = tx("T1", "Dinner"))
        assertThat(decision).isInstanceOf(MergeDecision.Conflict::class.java)
        assertThat((decision as MergeDecision.Conflict).diffs).isNotEmpty()
        assertThat(decision.needsConflictReview).isTrue()
    }

    // ------------------------------------------------- §7.2: deletion vs edit

    @Test
    fun `a remote deletion of an unchanged record deletes locally too`() {
        val base = tx("T1", "Lunch")
        assertThat(decide(base = base, local = base, remote = null)).isEqualTo(MergeDecision.DeleteLocal)
    }

    @Test
    fun `a local deletion of an unchanged record deletes remotely too`() {
        val base = tx("T1", "Lunch")
        assertThat(decide(base = base, local = null, remote = base)).isEqualTo(MergeDecision.DeleteRemote)
    }

    @Test
    fun `both sides deleted the same record needs no change`() {
        val base = tx("T1", "Lunch")
        assertThat(decide(base = base, local = null, remote = null)).isEqualTo(MergeDecision.NoChange)
    }

    @Test
    fun `a remote edit beats a local delete but is surfaced for review`() {
        val base = tx("T1", "Lunch")
        val remote = tx("T1", "Dinner")
        val decision = decide(base = base, local = null, remote = remote)

        assertThat(decision).isInstanceOf(MergeDecision.DeleteVsEdit::class.java)
        assertThat((decision as MergeDecision.DeleteVsEdit).winner).isEqualTo(Side.Remote)
        assertThat(decision.diffs.map { it.field }).containsExactly("description")
        assertThat(decision.needsConflictReview).isTrue()
    }

    @Test
    fun `a local edit beats a remote delete but is surfaced for review`() {
        val base = tx("T1", "Lunch")
        val local = tx("T1", "Dinner")
        val decision = decide(base = base, local = local, remote = null)

        assertThat(decision).isInstanceOf(MergeDecision.DeleteVsEdit::class.java)
        assertThat((decision as MergeDecision.DeleteVsEdit).winner).isEqualTo(Side.Local)
        assertThat(decision.diffs.map { it.field }).containsExactly("description")
        assertThat(decision.needsConflictReview).isTrue()
    }

    @Test
    fun `a delete of a record the base never had is not a deletion at all`() {
        // local absent + base absent ⇒ local simply never had it.
        assertThat(decide(base = null, local = null, remote = tx("T1", "Lunch")))
            .isEqualTo(MergeDecision.TakeRemote)
    }

    // ------------------------------------------------------ §7.4: idempotency

    @Test
    fun `replaying the same snapshot after adopting remote produces no further change`() {
        val base = tx("T1", "Lunch")
        val remote = tx("T1", "Dinner")

        val first = decide(base = base, local = base, remote = remote)
        assertThat(first).isEqualTo(MergeDecision.TakeRemote)

        // Adopting remote must also re-base it (plan §7.4) — otherwise the next
        // round sees the very same difference and reports the change forever.
        val second = decide(base = remote, local = remote, remote = remote)
        assertThat(second).isEqualTo(MergeDecision.NoChange)
    }

    @Test
    fun `a conflict stops reappearing once the adopted result becomes the base`() {
        val base = tx("T1", "Lunch")
        val local = tx("T1", "Dinner")
        val remote = tx("T1", "Brunch")
        assertThat(decide(base, local, remote)).isInstanceOf(MergeDecision.Conflict::class.java)

        // User adopts the local version everywhere and it becomes the new base.
        assertThat(decide(base = local, local = local, remote = local))
            .isEqualTo(MergeDecision.NoChange)
    }

    // ------------------------------------------- §7.3: deletion is never inferred

    @Test
    fun `an incomplete listing writes nothing at all`() {
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a", "b"),
            present = emptySet(),
            previousPending = mapOf("c" to 5L),
            now = 100L,
            listingComplete = false
        )
        assertThat(plan.abandoned).isTrue()
        assertThat(plan.tombstones).isEmpty()
        // Even the pending set is left untouched — a failed listing proves nothing.
        assertThat(plan.pending).isEqualTo(mapOf("c" to 5L))
    }

    @Test
    fun `an empty listing while records were known is not trusted`() {
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a"),
            present = emptySet(),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true
        )
        assertThat(plan.abandoned).isTrue()
        assertThat(plan.tombstones).isEmpty()
        assertThat(plan.pending).isEmpty()
    }

    @Test
    fun `the first absence only marks the key as pending`() {
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a", "b"),
            present = setOf("b"),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true
        )
        assertThat(plan.abandoned).isFalse()
        assertThat(plan.tombstones).isEmpty()
        assertThat(plan.pending).isEqualTo(mapOf("a" to 100L))
    }

    @Test
    fun `a second consecutive absence promotes the key to a tombstone`() {
        // `keeper` stays present on purpose: a listing that came back *entirely*
        // empty is refused wholesale by the second gate, so "the whole listing is
        // empty" is a different (and deliberately untrusted) scenario.
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a", "keeper"),
            present = setOf("keeper"),
            previousPending = mapOf("a" to 50L),
            now = 100L,
            listingComplete = true
        )
        assertThat(plan.tombstones).containsExactly("a")
        assertThat(plan.pending).isEmpty()
    }

    @Test
    fun `a key that only lives in the previous pending set still reaches confirmation`() {
        // The trap: once a key goes missing it drops out of `watched`, so iterating
        // `watched` alone would leave it pending forever and the deletion would
        // never converge. `planDeletions` unions previousPending in on purpose.
        val plan = ThreeWayMerge.planDeletions(
            watched = emptySet(),
            present = setOf("b"),
            previousPending = mapOf("a" to 50L),
            now = 100L,
            listingComplete = true
        )
        assertThat(plan.tombstones).containsExactly("a")
    }

    @Test
    fun `a key that comes back clears its pending entry`() {
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a", "b"),
            present = setOf("a", "b"),
            previousPending = mapOf("a" to 50L),
            now = 100L,
            listingComplete = true
        )
        assertThat(plan.tombstones).isEmpty()
        assertThat(plan.pending).isEmpty()
    }

    @Test
    fun `a reappearing key restarts the two round count`() {
        // absent (round 1) → present (clears) → absent again must be a fresh round 1.
        // `keeper` is present in every round so the listing is never wholly empty
        // (which the second gate refuses to act on at all).
        val vanished = ThreeWayMerge.planDeletions(
            watched = setOf("a", "keeper"),
            present = setOf("keeper"),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true
        )
        assertThat(vanished.pending).isEqualTo(mapOf("a" to 100L))

        val cameBack = ThreeWayMerge.planDeletions(
            watched = setOf("a", "keeper"),
            present = setOf("a", "keeper"),
            previousPending = vanished.pending,
            now = 200L,
            listingComplete = true
        )
        assertThat(cameBack.pending).isEmpty()

        val vanishedAgain = ThreeWayMerge.planDeletions(
            watched = setOf("a", "keeper"),
            present = setOf("keeper"),
            previousPending = cameBack.pending,
            now = 300L,
            listingComplete = true
        )
        assertThat(vanishedAgain.tombstones).isEmpty()
        assertThat(vanishedAgain.pending).isEqualTo(mapOf("a" to 300L))
    }

    @Test
    fun `a deletion made on this device is tombstoned in one round`() {
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a"),
            present = emptySet(),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true,
            locallyDeleted = setOf("a")
        )
        assertThat(plan.tombstones).containsExactly("a")
        assertThat(plan.pending).isEmpty()
    }

    @Test
    fun `a locally deleted key that was never watched still gets a marker`() {
        val plan = ThreeWayMerge.planDeletions(
            watched = emptySet(),
            present = emptySet(),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true,
            locallyDeleted = setOf("ghost")
        )
        assertThat(plan.tombstones).containsExactly("ghost")
    }

    @Test
    fun `a locally deleted key that is present again is not tombstoned`() {
        // The user deleted it and then wrote it back — the delete intent is void.
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a"),
            present = setOf("a"),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true,
            locallyDeleted = setOf("a")
        )
        assertThat(plan.tombstones).isEmpty()
    }

    @Test
    fun `deleting the very last record locally is still propagated`() {
        // Gate 2 (empty listing) must not swallow a deletion we made ourselves.
        val plan = ThreeWayMerge.planDeletions(
            watched = setOf("a"),
            present = emptySet(),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true,
            locallyDeleted = setOf("a")
        )
        assertThat(plan.abandoned).isFalse()
        assertThat(plan.tombstones).containsExactly("a")
    }

    @Test
    fun `an empty snapshot with nothing ever watched is a normal no-op`() {
        val plan: DeletionPlan = ThreeWayMerge.planDeletions(
            watched = emptySet(),
            present = emptySet(),
            previousPending = emptyMap(),
            now = 100L,
            listingComplete = true
        )
        assertThat(plan.abandoned).isFalse()
        assertThat(plan.tombstones).isEmpty()
    }
}
