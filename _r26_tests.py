"""R26 测试更新：分块事务 / 主线程不留 DB 读 / 图型选择器 / 「图表」已删 / 跨块写入。"""
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
    print("OK", rel, "edits=", len(edits), "bytes=", len(out))


# ------------------------------------------------- SearchReportLoaderTest：图表数据
LOADER_TEST_ANCHOR = """    // The "account cannot be named" guard in `SearchReportLoader.accountName` cannot be
"""

LOADER_TEST_NEW = """    @Test
    fun `the chart draws one currency and leaves the others to the table`() {
        val home = debitAccount.commodity.currencyCode
        val eur = commoditiesDbAdapter.getCurrency("EUR")!!
        assertThat(eur.currencyCode).isNotEqualTo(home)
        val eurDebit = Account("Assets:EuroWallet").apply { commodity = eur }
        val eurCredit = Account("Expenses:Travel").apply { commodity = eur }
        accountsDbAdapter.addRecord(eurDebit)
        accountsDbAdapter.addRecord(eurCredit)
        saveIn("机票", BigDecimal("100.00"), eurDebit, eurCredit, eur)

        val report = SearchReportLoader.load(context, "1", transactionsDbAdapter, accountsDbAdapter)

        // €100 beats the home currency's 75.50, so the chart follows the money that was
        // actually searched for instead of the book's default currency. The home rows are
        // not dropped from the report — they are left to the table and named here, because
        // a chart that silently ignores part of the result set is worse than no chart.
        assertThat(report.chartCurrencyCode).isEqualTo(eur.currencyCode)
        assertThat(report.otherCurrencyCodes).containsExactly(home)
        assertThat(report.byAccount.map { it.amount.toBigDecimal() })
            .containsExactly(BigDecimal("100.00"))
        assertThat(report.hasChart).isTrue()
    }

    @Test
    fun `every matched row of the charted currency is in exactly one account bucket`() {
        val report = SearchReportLoader.load(context, "1", transactionsDbAdapter, accountsDbAdapter)

        assertThat(report.chartCurrencyCode).isEqualTo(debitAccount.commodity.currencyCode)
        assertThat(report.otherCurrencyCodes).isEmpty()
        assertThat(report.byAccount.map { it.count }.sum()).isEqualTo(3)
        assertThat(report.byAccount.sumOf { it.amount.toBigDecimal() })
            .isEqualByComparingTo("75.50")
    }

    @Test
    fun `the day buckets are days, oldest first, and cover every row`() {
        val zone = TimeZone.getTimeZone("UTC")
        val report = SearchReportLoader.load(
            context, "1", transactionsDbAdapter, accountsDbAdapter, zone
        )

        // Computed independently of the loader, with the same zone handed to both.
        val expectedDays = listOf(lunch, taxi, movie)
            .map { LocalDate(it.datePosted, DateTimeZone.UTC).toString() }
            .sorted()
        assertThat(report.byDate.map { it.label }).isEqualTo(expectedDays)
        assertThat(report.byDate.map { it.sortKey }).isSorted()
        assertThat(report.byDate.map { it.count }.sum()).isEqualTo(3)
    }

    @Test
    fun `a report with nothing in it has no chart`() {
        val report = loadLike("不存在的描述")

        assertThat(report.hasChart).isFalse()
        assertThat(report.chartCurrencyCode).isNull()
        assertThat(report.byAccount).isEmpty()
        assertThat(report.byDate).isEmpty()
    }

""" + LOADER_TEST_ANCHOR

patch("app/src/test/java/org/gnucash/android/ui/report/SearchReportLoaderTest.kt", [
    ("""import org.gnucash.android.ui.report.search.SearchReportLoader
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
""",
     """import org.gnucash.android.ui.report.search.SearchReportLoader
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.TimeZone
"""),
    (LOADER_TEST_ANCHOR, LOADER_TEST_NEW),
])

# ------------------------------------- SearchBulkWiringTest：分块事务 + 主线程不留 DB 读
BULK_GUARD_OLD = """    /**
     * The freeze the user reported ("保存 后卡住、数据没写入") is not the main
     * thread any more — the write already runs off it. It is the *per-row commit*:
     * both adapters open a transaction of their own, so deleting or editing N rows
     * is N fsyncs. Nothing visible distinguishes "the batch is grinding through
     * thousands of commits" from "the app hung", which is why the fix has to be
     * pinned at the source shape rather than observed through the UI.
     */
    @Test
    fun `both bulk writes wrap the whole batch in a single transaction`() {
        val writer = sourceText(BULK_WRITER)
        assertThat(writer)
            .`as`("删除必须整批包一个事务")
            .contains("transactionsDbAdapter.beginTransaction()")
        assertThat(countOf(writer, "transactionsDbAdapter.beginTransaction()"))
            .`as`("delete 与 update 两条路径各一次")
            .isEqualTo(2)
        assertThat(countOf(writer, "transactionsDbAdapter.setTransactionSuccessful()"))
            .`as`("每个事务都要标记成功，否则整批回滚")
            .isEqualTo(2)
        assertThat(countOf(writer, "transactionsDbAdapter.endTransaction()"))
            .`as`("每个 begin 都要有配对的 end")
            .isEqualTo(2)
    }
"""

BULK_GUARD_NEW = """    /**
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
        val size = Regex("const val BATCH_SIZE = (\\\\d+)").find(writer)
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
"""

patch("app/src/test/java/org/gnucash/android/ui/search/SearchBulkWiringTest.kt", [
    (BULK_GUARD_OLD, BULK_GUARD_NEW),
])

# ------------------------------------------- SearchReportWiringTest：图型 + 「图表」已删
WIRING_ANCHOR = """    companion object {
        private const val SEARCH_FRAGMENT =
"""

WIRING_NEW = """    @Test
    fun `the report screen offers a chart type and the array matches the constants`() {
        val fragment = sourceText(SEARCH_REPORT_FRAGMENT)
        assertThat(fragment)
            .`as`("图型下拉必须由资源数组填充")
            .contains("R.array.search_report_chart_types")

        // The spinner answers with a position into the array, and the fragment branches on
        // its own constants. The two lists have to line up item by item — the same trap as
        // the report-type spinner, one level down.
        val items = arrayItems(sourceText(ZH_STRINGS), "search_report_chart_types")
        assertThat(items).containsExactly("表格", "饼图", "柱状图", "折线图")
        val constants = CHART_CONSTANTS.map { name ->
            Regex("const val $name = (\\\\d+)").find(fragment)?.groupValues?.get(1)?.toInt()
        }
        assertThat(constants).`as`("四个图型常量都要在场").doesNotContainNull()
        assertThat(constants).`as`("常量必须按数组顺序递增").isEqualTo(listOf(0, 1, 2, 3))
        assertThat(items).`as`("元素个数必须与分支数一致").hasSize(CHART_CONSTANTS.size)
    }

    @Test
    fun `the chart types are built and the table stays the default`() {
        val fragment = sourceText(SEARCH_REPORT_FRAGMENT)
        assertThat(fragment)
            .`as`("三种图各自有构建点")
            .contains("PieChart(context)")
            .contains("BarChart(context)")
            .contains("LineChart(context)")
        assertThat(fragment)
            .`as`("折线图按日期、饼图/柱状图按账户")
            .contains("if (byDate) data.byDate else data.byAccount")
        assertThat(fragment)
            .`as`("默认落在表格上（合计列表就是用户说"正常"的那个）")
            .contains("var chartType: Int = CHART_TYPE_TABLE")
    }

    @Test
    fun `the search screen no longer has a chart dialog`() {
        // The old 图表 popup was a one-off picture of the same data that could not be
        // switched, sorted or totalled — the report screen now answers that question, and
        // leaving the menu entry behind would leave two half-answers.
        val menu = sourceText(SEARCH_MENU)
        assertThat(menu).doesNotContain("menu_search_chart")
        assertThat(sourceText(SEARCH_FRAGMENT)).doesNotContain("SearchChartDialogFragment")
        assertThat(exists(SEARCH_CHART_DIALOG))
            .`as`("弹窗本体也必须删掉，只删入口会留下死代码")
            .isFalse()
    }

    /** Walks up from the test's working directory, the way [sourceText] does. */
    private fun exists(relative: String): Boolean {
        val candidates = listOf(File(relative), File("app/$relative"))
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            for (candidate in candidates) {
                if (File(dir, candidate.path).isFile) return true
            }
            dir = dir.parentFile ?: return false
        }
    }

    /** `<item>` values of a `<string-array>` in a resource file, in document order. */
    private fun arrayItems(xml: String, name: String): List<String> {
        val start = xml.indexOf("<string-array name=\\"$name\\">")
        assertThat(start).`as`("数组 $name 必须存在").isGreaterThanOrEqualTo(0)
        val block = xml.substring(start, xml.indexOf("</string-array>", start))
        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .map { it.groupValues[1].trim() }
            .toList()
    }

""" + WIRING_ANCHOR

patch("app/src/test/java/org/gnucash/android/ui/report/SearchReportWiringTest.kt", [
    ("import java.io.File\n", "import java.io.File\n"),
    ("""    companion object {
        private const val SEARCH_FRAGMENT =
            "src/main/java/org/gnucash/android/ui/search/SearchResultsFragment.kt"
        private const val SEARCH_MENU = "src/main/res/menu/search_results_menu.xml"
""",
     """    companion object {
        /** Must match the order of `R.array.search_report_chart_types`. */
        private val CHART_CONSTANTS = listOf(
            "CHART_TYPE_TABLE", "CHART_TYPE_PIE", "CHART_TYPE_BAR", "CHART_TYPE_LINE"
        )

        private const val SEARCH_FRAGMENT =
            "src/main/java/org/gnucash/android/ui/search/SearchResultsFragment.kt"
        private const val SEARCH_MENU = "src/main/res/menu/search_results_menu.xml"
        private const val ZH_STRINGS = "src/main/res/values-zh-rCN/strings.xml"
        private const val SEARCH_CHART_DIALOG =
            "src/main/java/org/gnucash/android/ui/search/SearchChartDialogFragment.kt"
"""),
])

# --------------------------------- SearchBulkWriterTest：跨块边界全部写入（真库）
BULK_DB_OLD = """    fun `a wide batch writes every row exactly once`() {
        val uids = (1..60).map { index ->
            save("批量 $index", "", millis(2024, 6, 1, 0, index % 60, 0)).uid
        }
        assertThat(uidOfAll()).hasSize(63)

        assertThat(writer.update(uids, BulkEditField.Number, "N", false)).isEqualTo(60)
        assertThat(writer.delete(uids)).isEqualTo(60)

        // Only the three fixtures planted in setUp survive.
        assertThat(uidOfAll()).containsExactlyInAnyOrder(lunch.uid, taxi.uid, movie.uid)
    }
"""

BULK_DB_NEW = """    fun `a batch wider than one chunk writes every row exactly once`() {
        // Two full chunks plus a partial one. The chunking is what keeps the write lock
        // from being held for the whole batch, and the chunk boundary is exactly where a
        // row can be silently skipped — every row has to land, in every chunk.
        val count = SearchBulkWriter.BATCH_SIZE * 2 + 7
        val uids = (1..count).map { index ->
            save("批量 $index", "", millis(2024, 6, 1, 0, index % 60, 0)).uid
        }
        assertThat(uidOfAll()).hasSize(count + 3)

        assertThat(writer.update(uids, BulkEditField.Number, "N", false)).isEqualTo(count)
        assertThat(uids.all { reload(it).number == "N" })
            .`as`("每一块都要真的写进去，不只是最后一块")
            .isTrue()

        assertThat(writer.delete(uids)).isEqualTo(count)

        // Only the three fixtures planted in setUp survive.
        assertThat(uidOfAll()).containsExactlyInAnyOrder(lunch.uid, taxi.uid, movie.uid)
    }
"""

patch("app/src/test/java/org/gnucash/android/test/unit/db/SearchBulkWriterTest.kt", [
    (BULK_DB_OLD, BULK_DB_NEW),
])
