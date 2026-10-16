package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * The one thing the pieces of a bulk action cannot show on their own: that the
 * write really is handed to [BulkActionRunner] instead of being run inline.
 *
 * On a handful of rows both versions behave identically, so nothing in the app
 * would ever look wrong — the difference only shows up on a large selection,
 * where one version freezes the screen and the other does not. That is not
 * something a JVM test can observe through the UI, so the shape is pinned here.
 */
class SearchBulkWiringTest {

    private fun sourceText(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            for (candidate in candidates) {
                val file = File(dir, candidate.path)
                if (file.isFile) return file.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `both bulk writes are handed to the runner`() {
        val viewModel = sourceText(VIEW_MODEL)
        assertThat(viewModel)
            .`as`("the bulk delete must not run on the main thread")
            .contains("bulkRunner.run({ bulkWriter.delete(uids) })")
        assertThat(viewModel)
            .`as`("the bulk edit must not run on the main thread")
            .contains("bulkRunner.run({ bulkWriter.update(uids, field, rawValue, tagRemove) })")
    }

    @Test
    fun `the screen does not read a count synchronously`() {
        val fragment = sourceText(FRAGMENT)
        assertThat(fragment).doesNotContain("val updated = viewModel.bulkUpdate(")
        assertThat(fragment).doesNotContain("val deleted = viewModel.deleteTransactions(")
        assertThat(fragment).contains("viewModel.bulkUpdate(uids, field, value, tagRemove) {")
        assertThat(fragment).contains("viewModel.deleteTransactions(uids) {")
    }

    @Test
    fun `the action bar closes before the batch starts`() {
        val fragment = sourceText(FRAGMENT)
        val closed = fragment.indexOf("finishSelection()")
        val started = fragment.indexOf("viewModel.bulkUpdate(uids, field, value, tagRemove) {")
        assertThat(closed).`as`("先关选择态").isGreaterThanOrEqualTo(0)
        assertThat(started).`as`("批写入口必须存在").isGreaterThanOrEqualTo(0)
        assertThat(started)
            .`as`("关选择态必须在批写之前：批写期间列表要保持可用")
            .isGreaterThan(closed)
    }

    /**
     * The freeze the user reported is not the main thread any more — the write already
     * runs off it — and it is not the per-row commit either: both adapters open a
     * transaction of their own, so N rows is N fsyncs, and that was already fixed.
     *
     * What is left is the *other* extreme. Wrapping the whole batch in ONE transaction
     * fixes the fsync storm and creates a worse problem: the batch holds the database's
     * write lock from the first row to the last, and the result list reads the database
     * on the main thread every time it rebinds. The main thread then waits behind the
     * write for as long as the batch takes, the user gets an ANR dialog, the process is
     * killed mid-transaction and *nothing* is written — "GnuCash dev 没有响应" followed by
     * an empty result. Hence: bounded chunks, with a breath in between.
     */
    @Test
    fun `both bulk writes commit in bounded chunks instead of one long transaction`() {
        val writer = sourceText(BULK_WRITER)
        assertThat(countOf(writer, "transactionsDbAdapter.beginTransaction()"))
            .`as`("delete 与 update 两条路径各一次（在各自的分块循环里）")
            .isEqualTo(2)
        assertThat(countOf(writer, "transactionsDbAdapter.setTransactionSuccessful()"))
            .`as`("每个事务都要标记成功，否则整块回滚掉")
            .isEqualTo(2)
        assertThat(countOf(writer, "transactionsDbAdapter.endTransaction()"))
            .`as`("每个 begin 都要有配对的 end")
            .isEqualTo(2)
        assertThat(countOf(writer, "uids.chunked(BATCH_SIZE)"))
            .`as`("两条路径都必须分块")
            .isEqualTo(2)
        assertThat(countOf(writer, "breathe()"))
            .`as`("每块之间要把写锁让出去一次（delete 1 次调用 + update 1 次 + 1 处定义）")
            .isEqualTo(3)

        // A chunk so wide it is the whole batch again would defeat the fix silently.
        val size = Regex("const val BATCH_SIZE = (\\d+)").find(writer)
        assertThat(size).`as`("BATCH_SIZE 必须是可读的常量").isNotNull()
        assertThat(size!!.groupValues[1].toInt())
            .`as`("单块不能过大，否则写锁又等于整批持有")
            .isBetween(1, 200)
    }

    /**
     * The second half of the same ANR: the summary rows resolve an account name and the
     * tags per matched row, and both are **database reads**. Building them inside the
     * `Main` block put thousands of queries on the main thread right after a bulk write.
     * No JVM test can see which thread a lambda ran on, so the shape is pinned.
     */
    @Test
    fun `the per-row database lookups are not built on the main thread`() {
        val viewModel = sourceText(VIEW_MODEL)
        assertThat(viewModel)
            .`as`("逐行查询必须在 IO 线程上先做完")
            .contains("val summaryRows = transactions.map { it.toSearchResultRow() }")

        val mainStart = viewModel.lastIndexOf("withContext(Dispatchers.Main) {")
        val emit = viewModel.indexOf("_results.emit(cursor)")
        assertThat(mainStart).`as`("主线程块必须存在").isGreaterThanOrEqualTo(0)
        assertThat(emit).`as`("主线程块以 emit 收尾").isGreaterThan(mainStart)
        assertThat(viewModel.substring(mainStart, emit))
            .`as`("主线程块里不许再出现逐行的 DB 查询")
            .doesNotContain("toSearchResultRow()")
    }

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    companion object {
        private const val VIEW_MODEL =
            "src/main/java/org/gnucash/android/ui/search/SearchResultsViewModel.kt"
        private const val FRAGMENT =
            "src/main/java/org/gnucash/android/ui/search/SearchResultsFragment.kt"
        private const val BULK_WRITER =
            "src/main/java/org/gnucash/android/ui/search/SearchBulkWriter.kt"
    }
}
