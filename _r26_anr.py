"""R26 需求②：批量保存后的 ANR 与「数据没写入」。

根因（三件事叠加）：
  1. `SearchResultViewHolder.bind()` 在**主线程**做 3~5 次 DB 查询
     （`buildModelInstance` 查分录、`getFullyQualifiedAccountName` 查账户链、
      `getTagNamesForTransaction` 查标签）；
  2. `finishSelection()` 触发 `notifyDataSetChanged()`，紧接着就是一帧重绑；
  3. `SearchBulkWriter` 这批写**整批持一个 write 事务** ⇒ 主线程的绑定查询被写锁饿死 ⇒
     ANR（"GnuCash dev 没有响应"）；ANR 后进程被系统杀掉 ⇒ 事务从未提交 ⇒ 数据没写入，
     并被拉回首页。

修法：① 写路径改**分块提交**（块间让出，写锁不再被整批持有）；
      ② `SearchResultsViewModel.search()` 的逐行 DB 查询移出主线程。

文件混用 LF/CRLF ⇒ 读时归一、写时按原文件还原。
"""
import io
import os

ROOT = os.path.dirname(os.path.abspath(__file__))


def patch(rel, edits):
    path = os.path.join(ROOT, rel)
    raw = io.open(path, encoding="utf-8", newline="").read()
    crlf = raw.count("\r\n")
    lf = raw.count("\n") - crlf
    if crlf and lf:
        raise SystemExit("mixed line endings in " + rel)
    text = raw.replace("\r\n", "\n")
    for old, new in edits:
        n = text.count(old)
        if n != 1:
            raise SystemExit("anchor count %d for %r in %s" % (n, old[:70], rel))
        text = text.replace(old, new, 1)
    out = text.replace("\n", "\r\n") if crlf else text
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("OK", rel, "edits=", len(edits), "crlf=", crlf, "bytes=", len(out))


DELETE_OLD = """     * One `deleteRecord` per uid (the call the single-row delete already uses, so
     * splits, tag links and widgets behave identically) instead of a single
     * `DELETE ... IN (...)`. A failing uid is logged and skipped rather than
     * aborting the batch; the returned count lets the caller tell the truth about
     * what happened.
     */
    fun delete(uids: Collection<String>): Int {
        if (uids.isEmpty()) return 0
        // One transaction for the whole batch: a per-row commit is a per-row fsync, so
        // a wide "全选 → 删除" crawled for minutes with nothing on screen moving.
        // Failures are still skipped per row instead of aborting the batch.
        var deleted = 0
        transactionsDbAdapter.beginTransaction()
        try {
            for (uid in uids) {
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
        return deleted
    }
"""

DELETE_NEW = """     * One `deleteRecord` per uid (the call the single-row delete already uses, so
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
"""

UPDATE_OLD_HEAD = """        if (uids.isEmpty()) return 0
        // The whole batch goes in ONE transaction. `addRecord` opens (and therefore
        // commits) a transaction of its own per row, and 2000 committed rows is 2000
        // fsyncs: that is what made "全选 → 改值 → 保存" crawl for minutes after the
        // write had already been moved off the main thread — the screen stayed
        // responsive but the data never seemed to arrive. A row that throws is still
        // logged and skipped rather than aborting the batch.
        var updated = 0
        transactionsDbAdapter.beginTransaction()
        try {
            for (uid in uids) {
"""

UPDATE_NEW_HEAD = """        if (uids.isEmpty()) return 0
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
"""

UPDATE_TAIL_OLD = """                    updated++
                } catch (e: Exception) {
                    Timber.e(e, "Failed to bulk-edit transaction %s", uid)
                }
            }
            transactionsDbAdapter.setTransactionSuccessful()
        } finally {
            transactionsDbAdapter.endTransaction()
        }
        return updated
    }
}
"""

UPDATE_TAIL_NEW = """                        updated++
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
            // Cancelled mid-batch: chunks already committed stay committed, so stop here
            // and leave the flag set for the caller.
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
"""

patch("app/src/main/java/org/gnucash/android/ui/search/SearchBulkWriter.kt", [
    (DELETE_OLD, DELETE_NEW),
    (UPDATE_OLD_HEAD, UPDATE_NEW_HEAD),
    (UPDATE_TAIL_OLD, UPDATE_TAIL_NEW),
])

VIEWMODEL_OLD = """            val transactions = readTransactions(cursor)

            withContext(Dispatchers.Main) {
                _summary.value = SearchSummary.of(transactions.map { it.toSearchResultRow() })
                _results.emit(cursor)
            }
"""

VIEWMODEL_NEW = """            val transactions = readTransactions(cursor)

            // `toSearchResultRow` resolves the account name and the tags, and both are
            // database reads — one pair per matched row. Doing that inside the `Main`
            // block below put thousands of queries on the main thread right after a bulk
            // write, which is what turned "保存" into an ANR. They run here, on the IO
            // thread, and the main thread only assigns the finished list.
            val summaryRows = transactions.map { it.toSearchResultRow() }

            withContext(Dispatchers.Main) {
                _summary.value = SearchSummary.of(summaryRows)
                _results.emit(cursor)
            }
"""

patch("app/src/main/java/org/gnucash/android/ui/search/SearchResultsViewModel.kt", [
    (VIEWMODEL_OLD, VIEWMODEL_NEW),
])
