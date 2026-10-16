package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.ConflictDigest
import org.gnucash.android.sync.ConflictKind
import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.FieldDeltaKind
import org.gnucash.android.sync.FieldDiff
import org.gnucash.android.sync.MergeDecision
import org.gnucash.android.sync.Side
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.fieldDiffs
import org.junit.Test

/**
 * How a parked record is classified for the conflict centre.
 *
 * Pure JVM, and deliberately pointed at the **classification** rather than the wording: what
 * the user is told is only as good as which situation we decided they were in. Two of these
 * would fail silently in the app — reading the kind off a decision that is not reviewable, and
 * guessing "both edited" for a record whose payload could not be read.
 */
class ConflictDigestTest {

    private val id = SyncIdentity(SyncRecordType.TRANSACTION, "8f2a1b3c")

    private fun diff(
        field: String = "notes",
        base: String? = "old",
        local: String? = "mine",
        remote: String? = "theirs"
    ) = FieldDiff(field, base, local, remote)

    // ---------------------------------------------------------------- kindOf

    @Test
    fun `a conflict decision is both sides edited`() {
        assertThat(ConflictDigest.kindOf(MergeDecision.Conflict(listOf(diff()))))
            .isEqualTo(ConflictKind.BOTH_EDITED)
    }

    @Test
    fun `a delete-versus-edit names the side that was deleted`() {
        // Which side was deleted is the whole point of the row: it says which change was kept.
        assertThat(ConflictDigest.kindOf(MergeDecision.DeleteVsEdit(Side.Local, listOf(diff()))))
            .isEqualTo(ConflictKind.DELETED_THERE_EDITED_HERE)
        assertThat(ConflictDigest.kindOf(MergeDecision.DeleteVsEdit(Side.Remote, listOf(diff()))))
            .isEqualTo(ConflictKind.DELETED_HERE_EDITED_THERE)
    }

    @Test
    fun `no decision at all means the record could not be read`() {
        // `null` is only ever produced for a record parked because its payload was
        // unreadable; showing it as "both edited" would invent a conflict that is not there.
        assertThat(ConflictDigest.kindOf(null)).isEqualTo(ConflictKind.UNREADABLE)
    }

    @Test
    fun `a decision that is never parked is not described as a conflict`() {
        // The planner parks only the two reviewable kinds. If it ever parks something else,
        // the honest answer is "cannot show you this", not a plausible wrong story.
        val notReviewable = listOf(
            MergeDecision.NoChange,
            MergeDecision.KeepLocal,
            MergeDecision.TakeRemote,
            MergeDecision.DeleteLocal,
            MergeDecision.DeleteRemote
        )
        for (decision in notReviewable) {
            assertThat(ConflictDigest.kindOf(decision))
                .`as`("$decision")
                .isEqualTo(ConflictKind.UNREADABLE)
        }
    }

    // ---------------------------------------------------------------- fieldDiffs

    @Test
    fun `a reviewable decision carries its own field differences out`() {
        val d = diff()
        assertThat(MergeDecision.Conflict(listOf(d)).fieldDiffs).containsExactly(d)
        assertThat(MergeDecision.DeleteVsEdit(Side.Local, listOf(d)).fieldDiffs).containsExactly(d)
    }

    @Test
    fun `a decision that is not reviewable has no field differences`() {
        assertThat(MergeDecision.NoChange.fieldDiffs).isEmpty()
        assertThat(MergeDecision.KeepLocal.fieldDiffs).isEmpty()
    }

    // ---------------------------------------------------------------- deltaKindOf

    @Test
    fun `with no common ancestor the sides are described as additions`() {
        assertThat(ConflictDigest.deltaKindOf(diff(base = null, local = "a", remote = "b")))
            .isEqualTo(FieldDeltaKind.BOTH_CHANGED)
        assertThat(ConflictDigest.deltaKindOf(diff(base = null, local = "a", remote = null)))
            .isEqualTo(FieldDeltaKind.ONLY_LOCAL)
        assertThat(ConflictDigest.deltaKindOf(diff(base = null, local = null, remote = "b")))
            .isEqualTo(FieldDeltaKind.ONLY_REMOTE)
    }

    @Test
    fun `a side that lost a field the ancestor had is reported as dropping it`() {
        assertThat(ConflictDigest.deltaKindOf(diff(base = "old", local = null, remote = "r")))
            .isEqualTo(FieldDeltaKind.LOCAL_DROPPED)
        assertThat(ConflictDigest.deltaKindOf(diff(base = "old", local = "l", remote = null)))
            .isEqualTo(FieldDeltaKind.REMOTE_DROPPED)
    }

    @Test
    fun `a field only one side moved is attributed to that side`() {
        assertThat(ConflictDigest.deltaKindOf(diff(base = "old", local = "old", remote = "new")))
            .isEqualTo(FieldDeltaKind.REMOTE_CHANGED)
        assertThat(ConflictDigest.deltaKindOf(diff(base = "old", local = "new", remote = "old")))
            .isEqualTo(FieldDeltaKind.LOCAL_CHANGED)
    }

    @Test
    fun `both sides moving the field differently is the conflict case`() {
        assertThat(ConflictDigest.deltaKindOf(diff(base = "old", local = "mine", remote = "theirs")))
            .isEqualTo(FieldDeltaKind.BOTH_CHANGED)
    }

    @Test
    fun `a field neither side has left is its own case, not a change`() {
        // Reachable through a delete-versus-edit diff, whose survivor may itself have lost a
        // field the ancestor carried. Calling that "both changed" would point the user at a
        // field that is simply gone.
        assertThat(ConflictDigest.deltaKindOf(diff(base = "old", local = null, remote = null)))
            .isEqualTo(FieldDeltaKind.BOTH_DROPPED)
    }

    // ---------------------------------------------------------------- of

    @Test
    fun `rows come out in the same order the round used`() {
        val reviews = listOf(
            ConflictReview(SyncIdentity(SyncRecordType.TRANSACTION, "bbb"), "n", MergeDecision.Conflict(emptyList())),
            ConflictReview(SyncIdentity(SyncRecordType.ACCOUNT, "aaa"), "n", MergeDecision.Conflict(emptyList())),
            ConflictReview(SyncIdentity(SyncRecordType.TRANSACTION, "aaa"), "n", MergeDecision.Conflict(emptyList()))
        )
        assertThat(ConflictDigest.of(reviews).map { it.identity.token })
            .containsExactly("account:aaa", "transaction:aaa", "transaction:bbb")
    }

    @Test
    fun `an unreadable record becomes a row with nothing to compare`() {
        // `diffs` must stay empty rather than being filled with something: the screen has to
        // be able to say "no comparable differences" instead of showing an empty table.
        val row = ConflictDigest.of(listOf(ConflictReview(id, "unreadable"))).single()
        assertThat(row.kind).isEqualTo(ConflictKind.UNREADABLE)
        assertThat(row.diffs).isEmpty()
    }

    @Test
    fun `the diffs behind a conflict reach the row intact`() {
        val ds = listOf(diff("name"), diff("notes"))
        val row = ConflictDigest.of(listOf(ConflictReview(id, "n", MergeDecision.Conflict(ds)))).single()
        assertThat(row.diffs).containsExactlyElementsOf(ds)
    }

    @Test
    fun `no reviews means no rows`() {
        assertThat(ConflictDigest.of(emptyList())).isEmpty()
    }
}
