# -*- coding: utf-8 -*-
"""R25-D：搜索结果接入「报表」。"""
import sys

sys.path.insert(0, ".")
from _r25_apply import patch  # noqa: E402

EN = "app/src/main/res/values/strings.xml"
ZH = "app/src/main/res/values-zh-rCN/strings.xml"
RT = "app/src/main/java/org/gnucash/android/ui/report/ReportType.kt"
RA = "app/src/main/java/org/gnucash/android/ui/report/ReportsActivity.kt"
SMENU = "app/src/main/res/menu/search_results_menu.xml"
SFRAG = "app/src/main/java/org/gnucash/android/ui/search/SearchResultsFragment.kt"

patch(EN, [(
    '''    <string name="label_search_report_total">Total</string>
''',
    '''    <string name="label_search_report_total">Total</string>
    <string name="label_search_report_count">%1$d transactions</string>
''')])
patch(ZH, [(
    '''    <string name="label_search_report_total">合计</string>
''',
    '''    <string name="label_search_report_total">合计</string>
    <string name="label_search_report_count">共 %1$d 笔</string>
''')])

# ------------------------------------------------------------------------------ ReportType
OLD_RT_IMPORT = '''import org.gnucash.android.ui.report.sheet.BalanceSheetFragment
'''
NEW_RT_IMPORT = '''import org.gnucash.android.ui.report.search.SearchResultReportFragment
import org.gnucash.android.ui.report.sheet.BalanceSheetFragment
'''

OLD_RT_ENUM = '''    SHEET(
        R.string.title_balance_sheet_report,
        R.color.report_purple,
        BalanceSheetFragment::class.java
    ),
    NONE(
        R.string.title_reports,
        R.color.report_green,
        ReportsOverviewFragment::class.java
    );
'''
NEW_RT_ENUM = '''    SHEET(
        R.string.title_balance_sheet_report,
        R.color.report_purple,
        BalanceSheetFragment::class.java
    ),

    /**
     * The transactions a search matched, rendered as a report.
     *
     * ⚠️ Order matters: the toolbar spinner answers with `values()[position]`, so the
     * entries that may be left out ([SEARCH_RESULT] when nothing was searched, [NONE]
     * always) must be the LAST ones. A hole in the middle would make every entry below it
     * open the wrong report.
     */
    SEARCH_RESULT(
        R.string.title_search_report,
        R.color.report_purple,
        SearchResultReportFragment::class.java
    ),
    NONE(
        R.string.title_reports,
        R.color.report_green,
        ReportsOverviewFragment::class.java
    );
'''

OLD_RT_NAMES = '''        fun getReportNames(context: Context): List<String> {
            val names = mutableListOf<String>()
            for (value in _values) {
                if (value == NONE) continue
                names.add(context.getString(value.titleId))
            }
            return names
        }
'''
NEW_RT_NAMES = '''        /**
         * @param includeSearchResult whether 搜索结果 belongs in the list. It is only
         *        meaningful once a search has been run, and leaving it out keeps the
         *        spinner free of an entry that can never draw anything.
         */
        fun getReportNames(context: Context, includeSearchResult: Boolean = true): List<String> {
            val names = mutableListOf<String>()
            for (value in _values) {
                if (value == NONE) continue
                if (value == SEARCH_RESULT && !includeSearchResult) continue
                names.add(context.getString(value.titleId))
            }
            return names
        }
'''
patch(RT, [
    (OLD_RT_IMPORT, NEW_RT_IMPORT),
    (OLD_RT_ENUM, NEW_RT_ENUM),
    (OLD_RT_NAMES, NEW_RT_NAMES),
])

# ------------------------------------------------------------------------- ReportsActivity
OLD_RA_TYPE = '''    private var reportType: ReportType = ReportType.NONE
'''
NEW_RA_TYPE = '''    private var reportType: ReportType = ReportType.NONE

    /**
     * The search predicate the reports are limited to, when this screen was opened from a
     * search result. `null` — the normal case — means the usual account/period reports.
     */
    var searchWhere: String? = null
        private set
'''

OLD_RA_SPINNER = '''        val actionBar: ActionBar = supportActionBar!!
        val typesAdapter = ArrayAdapter<String?>(
            actionBar.themedContext,
            android.R.layout.simple_list_item_1,
            getReportNames(context)
        )
'''
NEW_RA_SPINNER = '''        val actionBar: ActionBar = supportActionBar!!
        searchWhere = searchWhereOf(intent)
        val typesAdapter = ArrayAdapter<String?>(
            actionBar.themedContext,
            android.R.layout.simple_list_item_1,
            getReportNames(context, includeSearchResult = searchWhere != null)
        )
'''

OLD_RA_INITIAL = '''        if (savedInstanceState == null) {
            showOverview()
        } else {
'''
NEW_RA_INITIAL = '''        if (savedInstanceState == null) {
            // Opened from a search: land on that report. The overview's balances answer a
            // different question than the one the user just asked.
            if (searchWhere == null) showOverview() else showReport(ReportType.SEARCH_RESULT)
        } else {
'''

OLD_RA_ONNEW = '''    companion object {
        private const val STATE_REPORT_TYPE = "report_type"
'''
NEW_RA_ONNEW = '''    /**
     * A second `showSearchResult` while this screen is already up lands here, because the
     * launch flags below keep a single instance. [onCreate] — the only other reader of the
     * extra — does not run again, so without this the previous query would stay on screen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val where = searchWhereOf(intent) ?: return
        if (where == searchWhere) return
        searchWhere = where
        val binding = this.binding ?: return
        val actionBar: ActionBar = supportActionBar ?: return
        binding.toolbarLayout.toolbarSpinner.adapter = ArrayAdapter<String?>(
            actionBar.themedContext,
            android.R.layout.simple_list_item_1,
            getReportNames(this, includeSearchResult = true)
        )
        showReport(ReportType.SEARCH_RESULT)
    }

    /** @return the search predicate carried by [intent], or `null` when it is not a search launch. */
    private fun searchWhereOf(intent: Intent?): String? {
        if (intent == null || !intent.hasExtra(EXTRA_SEARCH_WHERE)) return null
        return intent.getStringExtra(EXTRA_SEARCH_WHERE).orEmpty()
    }

    companion object {
        private const val STATE_REPORT_TYPE = "report_type"
'''

OLD_RA_COMPANION = '''        fun show(context: Context) {
            val intent = Intent(context, ReportsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
'''
NEW_RA_COMPANION = '''        fun show(context: Context) {
            val intent = Intent(context, ReportsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }

        /**
         * Opens 报表 limited to the transactions a search matched.
         *
         * @param where the **very** WHERE fragment the result list was built from, so the
         *              report cannot drift from the list it was opened from.
         */
        fun showSearchResult(context: Context, where: String?) {
            val intent = Intent(context, ReportsActivity::class.java)
                .putExtra(EXTRA_SEARCH_WHERE, where.orEmpty())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }

        /** Marks the launch as "from a search"; its presence — not its value — is the flag. */
        private const val EXTRA_SEARCH_WHERE = "report_search_where"
'''
patch(RA, [
    (OLD_RA_TYPE, NEW_RA_TYPE),
    (OLD_RA_SPINNER, NEW_RA_SPINNER),
    (OLD_RA_INITIAL, NEW_RA_INITIAL),
    (OLD_RA_ONNEW, NEW_RA_ONNEW),
    (OLD_RA_COMPANION, NEW_RA_COMPANION),
])

# ------------------------------------------------------------------------ 搜索结果菜单 + 接线
OLD_SM = '''    <item
        android:id="@+id/menu_search_chart"
        android:title="@string/search_chart_title"
        app:showAsAction="never" />

</menu>'''
NEW_SM = '''    <item
        android:id="@+id/menu_search_chart"
        android:title="@string/search_chart_title"
        app:showAsAction="never" />

    <!-- 把当前结果集交给导航栏里那套「报表」显示 -->
    <item
        android:id="@+id/menu_report_search_results"
        android:title="@string/menu_report_search_results"
        app:showAsAction="never" />

</menu>'''
patch(SMENU, [(OLD_SM, NEW_SM)])

OLD_SF_IMPORT = '''import org.gnucash.android.ui.homescreen.WidgetConfigurationActivity.Companion.updateAllWidgets
'''
NEW_SF_IMPORT = '''import org.gnucash.android.ui.homescreen.WidgetConfigurationActivity.Companion.updateAllWidgets
import org.gnucash.android.ui.report.ReportsActivity
'''

OLD_SF_CASE = '''            R.id.menu_search_chart -> {
                SearchChartDialogFragment().show(childFragmentManager, SearchChartDialogFragment.TAG)
                true
            }
'''
NEW_SF_CASE = '''            R.id.menu_search_chart -> {
                SearchChartDialogFragment().show(childFragmentManager, SearchChartDialogFragment.TAG)
                true
            }

            R.id.menu_report_search_results -> {
                // Hand over the live query rather than re-deriving one from the widgets:
                // the report has to cover exactly the rows that are on screen.
                ReportsActivity.showSearchResult(requireContext(), viewModel.where)
                true
            }
'''
patch(SFRAG, [(OLD_SF_IMPORT, NEW_SF_IMPORT), (OLD_SF_CASE, NEW_SF_CASE)])
print("R25-D done")
