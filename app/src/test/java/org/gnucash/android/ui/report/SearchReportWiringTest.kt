package org.gnucash.android.ui.report

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * The join between the search screen and the report screen.
 *
 * Both halves already worked on their own — the search produced a WHERE fragment, the
 * report screen knew how to draw a table — and nothing connected them. The connection
 * is fragile in a way a unit test cannot see: the WHERE travels in an **intent extra**,
 * so a missing `putExtra`, a report entry that is always offered (even when nothing was
 * searched) or a fragment that forgets to ask the loader all look like a working app
 * that shows the wrong thing.
 */
class SearchReportWiringTest {

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
    fun `the result screen offers a report entry and hands over its query`() {
        val fragment = sourceText(SEARCH_FRAGMENT)
        assertThat(fragment)
            .`as`("溢出菜单里要有入口")
            .contains("R.id.menu_report_search_results")
        assertThat(fragment)
            .`as`("必须把当前查询一起带过去，否则报表不知道搜了什么")
            .contains("ReportsActivity.showSearchResult(requireContext(), viewModel.where)")

        val menu = sourceText(SEARCH_MENU)
        assertThat(menu).contains("@+id/menu_report_search_results")
    }

    @Test
    fun `the report screen carries the query in the intent`() {
        val activity = sourceText(REPORTS_ACTIVITY)
        assertThat(activity).contains("EXTRA_SEARCH_WHERE")
        assertThat(activity)
            .`as`("只有带查询时才把 搜索结果 放进报表列表")
            .contains("includeSearchResult = searchWhere != null")
        assertThat(activity)
            .`as`("从搜索过来时直接落在搜索结果报表上")
            .contains("showReport(ReportType.SEARCH_RESULT)")
        assertThat(activity)
            .`as`("第二次点击（activity 已在栈上）走 onNewIntent 而不是重建")
            .contains("override fun onNewIntent(")
    }

    @Test
    fun `the search report fragment reads the loader and drops the inapplicable spinners`() {
        val fragment = sourceText(SEARCH_REPORT_FRAGMENT)
        assertThat(fragment)
            .`as`("报表数据必须来自与结果列表同一套查询")
            .contains("SearchReportLoader.load(context)")
        assertThat(fragment).contains("ReportType.SEARCH_RESULT")
        assertThat(fragment)
            .`as`("结果集固定，账户类型/时间区间两个下拉不适用")
            .contains("requiresAccountTypeOptions(): Boolean = false")
            .contains("requiresTimeRangeOptions(): Boolean = false")
    }

    @Test
    fun `an account that cannot be named does not take the whole report down`() {
        // The branch cannot be reached through the database (`splits.account_uid` is NOT
        // NULL with a FOREIGN KEY onto `accounts`), so the shape is pinned here: a blank
        // uid answers an empty name, and a lookup that throws is swallowed rather than
        // aborting the report.
        val loader = sourceText(SEARCH_REPORT_LOADER)
        assertThat(loader)
            .`as`("空 uid 直接答空名")
            .contains("if (uid.isNullOrEmpty()) return \"\"")
        assertThat(loader)
            .`as`("查不到名字时兜住异常，而不是让整张报表崩掉")
            .contains("runCatching { accountsDbAdapter.getFullyQualifiedAccountName(uid) }")
            .contains(".getOrNull().orEmpty()")
    }

    @Test
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
            Regex("const val $name = (\\d+)").find(fragment)?.groupValues?.get(1)?.toInt()
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
            .`as`("默认落在表格上（合计列表就是用户说正常的那个）")
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
        val start = xml.indexOf("<string-array name=\"$name\">")
        assertThat(start).`as`("数组 $name 必须存在").isGreaterThanOrEqualTo(0)
        val block = xml.substring(start, xml.indexOf("</string-array>", start))
        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .map { it.groupValues[1].trim() }
            .toList()
    }

    companion object {
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
        private const val REPORTS_ACTIVITY =
            "src/main/java/org/gnucash/android/ui/report/ReportsActivity.kt"
        private const val SEARCH_REPORT_FRAGMENT =
            "src/main/java/org/gnucash/android/ui/report/search/SearchResultReportFragment.kt"
        private const val SEARCH_REPORT_LOADER =
            "src/main/java/org/gnucash/android/ui/report/search/SearchReportLoader.kt"
    }
}
