package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.SyncAbandon
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncNotice
import org.gnucash.android.sync.SyncNotices
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.SyncRoundResult
import org.junit.Test

/**
 * Which of the eight situations a finished round is reported as.
 *
 * Pure JVM: the whole point of [SyncNotices] is that "what does this round mean" is decided
 * from typed fields, so it can be asserted without a network, a database or a clock. What is
 * worth asserting here is mostly the **ordering** — several shapes are true at once in every
 * real round, and the branch that wins is the user's next move.
 */
class SyncNoticeTest {

    private fun review(key: String = "tx1") = ConflictReview(
        SyncIdentity(SyncRecordType.TRANSACTION, key),
        "both sides edited it"
    )

    private fun result(
        uploaded: Int = 0,
        downloaded: Int = 0,
        remoteDeleted: Int = 0,
        localDeleted: Int = 0,
        deferred: Int = 0,
        failed: Int = 0,
        reviews: List<ConflictReview> = emptyList(),
        restartNeeded: Boolean = false,
        abandoned: Boolean = false,
        abandonReason: SyncAbandon? = null
    ) = SyncRoundResult(
        completed = !abandoned,
        abandoned = abandoned,
        abandonReason = abandonReason,
        uploaded = uploaded,
        downloaded = downloaded,
        remoteDeleted = remoteDeleted,
        localDeleted = localDeleted,
        deferred = deferred,
        failed = failed,
        restartNeeded = restartNeeded,
        reviews = reviews
    )

    @Test
    fun `a round that agrees with the peer is the steady state and asks for no numbers`() {
        val notice = SyncNotices.of(result())
        assertThat(notice).isEqualTo(SyncNotice.NOTHING_TO_DO)
        // Six zeroes under "the two sides already agree" teach the user to stop reading the line.
        assertThat(notice.deservesCounts).isFalse()
    }

    @Test
    fun `a round that moved something is simply synced`() {
        assertThat(SyncNotices.of(result(uploaded = 2))).isEqualTo(SyncNotice.SYNCED)
        // A deletion counts as movement: the round did something the user should hear about.
        assertThat(SyncNotices.of(result(localDeleted = 1))).isEqualTo(SyncNotice.SYNCED)
    }

    @Test
    fun `an incomplete listing is reported as a retry rather than as a failure`() {
        val notice = SyncNotices.of(
            result(abandoned = true, abandonReason = SyncAbandon.LISTING_INCOMPLETE)
        )
        assertThat(notice).isEqualTo(SyncNotice.LISTING_INCOMPLETE)
        assertThat(notice.deservesCounts).isTrue()
    }

    @Test
    fun `an empty peer while records are known blames the folder, not the network`() {
        // The one outcome whose fix is on the settings page. Reporting it as a network error
        // sends the user to debug a server that answered perfectly well.
        assertThat(SyncNotices.of(result(abandoned = true, abandonReason = SyncAbandon.EMPTY_REMOTE)))
            .isEqualTo(SyncNotice.WRONG_FOLDER)
    }

    @Test
    fun `an abandoned round with no recorded reason falls back to the retry advice`() {
        assertThat(SyncNotices.of(result(abandoned = true)))
            .isEqualTo(SyncNotice.LISTING_INCOMPLETE)
    }

    @Test
    fun `an abandoned round outranks every other shape`() {
        // Nothing was written, so "3 records failed" is not merely secondary — it is not true:
        // no record was attempted at all.
        val notice = SyncNotices.of(
            result(abandoned = true, abandonReason = SyncAbandon.EMPTY_REMOTE, failed = 3, reviews = listOf(review()))
        )
        assertThat(notice).isEqualTo(SyncNotice.WRONG_FOLDER)
    }

    @Test
    fun `a restart outranks a failure and a conflict`() {
        // The peer moved under us: part of the plan was applied and the rest was not attempted.
        // "Try again" is the correct advice, and it is also correct for the failed record.
        val notice = SyncNotices.of(
            result(restartNeeded = true, failed = 1, reviews = listOf(review()))
        )
        assertThat(notice).isEqualTo(SyncNotice.RESTART_NEEDED)
    }

    @Test
    fun `a record that should have moved outranks a parked conflict`() {
        // A conflict is a question; this is data that is not where it was supposed to go.
        val notice = SyncNotices.of(result(failed = 1, reviews = listOf(review())))
        assertThat(notice).isEqualTo(SyncNotice.SOMETHING_FAILED)
    }

    @Test
    fun `a parked conflict outranks a deferred pull`() {
        val notice = SyncNotices.of(result(deferred = 2, reviews = listOf(review())))
        assertThat(notice).isEqualTo(SyncNotice.CONFLICTS_TO_REVIEW)
    }

    @Test
    fun `a deferred pull is not mistaken for the steady state`() {
        // Nothing moved, and the round did nothing wrong — but the peer's data is not arriving,
        // which is exactly what "nothing to do" would hide.
        val notice = SyncNotices.of(result(deferred = 2))
        assertThat(notice).isEqualTo(SyncNotice.DEFERRED_SOMETHING)
        assertThat(notice.deservesCounts).isTrue()
    }

    @Test
    fun `the counts are offered for every notice except the steady state`() {
        val quiet = SyncNotice.entries.filter { !it.deservesCounts }
        assertThat(quiet).containsExactly(SyncNotice.NOTHING_TO_DO)
    }

    @Test
    fun `the conflict count is derived from the reviews and cannot drift from them`() {
        // Counts used to be a field of their own; the list is the work item for the conflict
        // centre, and two fields could disagree about the same round.
        val round = result(reviews = listOf(review("a"), review("b")))
        assertThat(round.conflicts).isEqualTo(2)
        assertThat(round.reviews).hasSize(2)
    }

    @Test
    fun `a round that only deleted something counts as having moved`() {
        assertThat(result(remoteDeleted = 1).movedAnything).isTrue()
        assertThat(result().movedAnything).isFalse()
    }
}
