package org.gnucash.android.ui.search

import org.gnucash.android.db.adapter.DatabaseAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.util.TagCodec
import timber.log.Timber
import java.util.TimeZone

/**
 * The database half of the search-result bulk actions.
 *
 * Split out of [SearchResultsViewModel] on purpose: the ViewModel's job after a
 * bulk action is to re-run the query, and that needs a coroutine and the main
 * thread, which makes the write path awkward to assert. Here it is a plain
 * synchronous class over the real adapters — the tests drive it directly and the
 * ViewModel only delegates.
 */
class SearchBulkWriter(
    private val transactionsDbAdapter: TransactionsDbAdapter = TransactionsDbAdapter.instance,
    private val zone: TimeZone = TimeZone.getDefault()
) {

    /**
     * Deletes every uid, answering with how many rows actually went away.
     *
     * One `deleteRecord` per uid (the call the single-row delete already uses, so
     * splits, tag links and widgets behave identically) instead of a single
     * `DELETE ... IN (...)`. A failing uid is logged and skipped rather than
     * aborting the batch; the returned count lets the caller tell the truth about
     * what happened.
     */
    fun delete(uids: Collection<String>): Int {
        if (uids.isEmpty()) return 0
        // Committed in bounded chunks — never in one transaction over the whole batch.
        // A per-row commit is a per-row fsync (a wide "全选 → 删除" crawled for minutes),
        // but one transaction for the *entire* batch is just as wrong in the other
        // direction: it holds the database's write lock for as long as the batch runs,
        // so the main thread's list binding — which reads the database on every rebind —
        // waits behind it until the user gets an ANR dialog and the process is killed
        // mid-transaction, losing the whole batch. Chunks give both: one commit per
        // [BATCH_SIZE] rows instead of per row, and a lock that is never held longer
        // than a chunk takes.
        // Failures are still skipped per row instead of aborting the batch.
        var deleted = 0
        for (chunk in uids.chunked(BATCH_SIZE)) {
            transactionsDbAdapter.beginTransaction()
            try {
                for (uid in chunk) {
                    try {
                        if (transactionsDbAdapter.deleteRecord(uid)) deleted++
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to delete transaction %s", uid)
                    }
                }
                transactionsDbAdapter.setTransactionSuccessful()
            } finally {
                transactionsDbAdapter.endTransaction()
            }
            breathe()
        }
        return deleted
    }

    /**
     * Writes one field on N transactions, answering with how many were saved.
     *
     * The write goes through the **model** (`addRecord`), not a raw column
     * UPDATE: that is the path the transaction form takes, and it is the only one
     * that re-derives the tag mirror from the text. A column UPDATE would leave
     * `transaction_tags` stale while looking identical in `transactions`.
     *
     * Returns 0 when nothing could be applied (unknown uid, unparsable date,
     * unusable tag name) — the caller reports "nothing was updated" instead of
     * pretending it worked.
     */
    fun update(
        uids: Collection<String>,
        field: BulkEditField,
        rawValue: String?,
        tagRemove: Boolean
    ): Int {
        val tag = if (field.isTag) TagCodec.normalizeName(rawValue) else null
        // A blank tag name must not degrade into "clear every tag on the
        // selection" — that would be a destructive surprise from a typo.
        if (field.isTag && tag == null) return 0

        if (uids.isEmpty()) return 0
        // `addRecord` opens (and therefore commits) a transaction of its own per row, and
        // 2000 committed rows is 2000 fsyncs: that is what made "全选 → 改值 → 保存" crawl
        // for minutes after the write had already been moved off the main thread. The
        // cure is one commit per [BATCH_SIZE] rows — **not** one commit for the whole
        // batch, which trades the fsync storm for a write lock held for the entire run
        // (see [delete]). A row that throws is still logged and skipped rather than
        // aborting the batch.
        var updated = 0
        for (chunk in uids.chunked(BATCH_SIZE)) {
            transactionsDbAdapter.beginTransaction()
            try {
                for (uid in chunk) {
                    try {
                        val transaction = transactionsDbAdapter.getRecordOrNull(uid) ?: continue
                        when (field) {
                            BulkEditField.Description ->
                                transaction.description = BulkEdit.normalizeText(rawValue)

                            BulkEditField.Notes ->
                                transaction.notes = BulkEdit.normalizeText(rawValue)

                            BulkEditField.Number ->
                                transaction.number = BulkEdit.normalizeText(rawValue)

                            BulkEditField.Date -> {
                                // An unparsable date must SKIP this transaction, not save it
                                // unchanged and count it as an update — otherwise the user is
                                // told "40 updated" while the dates are all still the old ones.
                                val datePosted = BulkEdit.applyDate(
                                    transaction.datePosted, rawValue, zone
                                ) ?: continue
                                transaction.datePosted = datePosted
                            }

                            BulkEditField.Tag -> {
                                // Tags are text first and a mirror second: rewrite the
                                // notes, then let addRecord re-derive the mirror.
                                transaction.notes = if (tagRemove) {
                                    BulkEdit.removeTag(transaction.notes, tag!!)
                                } else {
                                    BulkEdit.addTag(transaction.notes, tag!!)
                                }
                                if (tagRemove) {
                                    // A token left in the description would be extracted
                                    // straight back into the mirror on the next save.
                                    transaction.description =
                                        BulkEdit.removeTag(transaction.description, tag!!)
                                }
                            }
                        }
                        transactionsDbAdapter.addRecord(
                            transaction, DatabaseAdapter.UpdateMethod.Update
                        )
                        updated++
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to bulk-edit transaction %s", uid)
                    }
                }
                transactionsDbAdapter.setTransactionSuccessful()
            } finally {
                transactionsDbAdapter.endTransaction()
            }
            breathe()
        }
        return updated
    }

    /**
     * Hands the database's write lock back for a moment between chunks.
     *
     * A reader that is already waiting — the result list rebinds and reads the database
     * on the main thread — otherwise loses the race to the next `beginTransaction()`,
     * because the lock is not fair. Two milliseconds per chunk is nothing next to a
     * chunk's own work, and it is the difference between "the list freezes until the
     * batch ends" and "the batch runs while the list stays usable".
     */
    private fun breathe() {
        try {
            Thread.sleep(BATCH_PAUSE_MILLIS)
        } catch (e: InterruptedException) {
            // The batch is not interruptible mid-row, so this only restores the flag for
            // the caller to notice; chunks already committed stay committed either way.
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /**
         * Rows written per transaction. Small enough that one chunk's write lock is a
         * blink for the main thread, large enough that a 2000-row batch is 40 commits
         * instead of 2000.
         */
        const val BATCH_SIZE = 50

        /** See [breathe]. */
        private const val BATCH_PAUSE_MILLIS = 2L
    }
}
