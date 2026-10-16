package org.gnucash.android.ui.report

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.report.search.SearchResultReportFragment
import org.junit.Test

/**
 * The one structural agreement the report screens depend on.
 *
 * `ReportsActivity` answers the toolbar spinner with `ReportType.values()[position]`, so
 * the visible entries and the enum have to line up position by position. `getReportNames`
 * drops some entries (NONE always, 搜索结果 when nothing was searched) — which is only safe
 * while the dropped ones are the **last** ones. If 搜索结果 is ever moved above the others,
 * every report below it would open the wrong screen, and nothing else would complain.
 */
class ReportTypeTest : GnuCashTest() {

    @Test
    fun `the entries a list may leave out are the trailing ones`() {
        val values = ReportType.values()
        assertThat(values[values.size - 1]).isEqualTo(ReportType.NONE)
        assertThat(values[values.size - 2]).isEqualTo(ReportType.SEARCH_RESULT)
    }

    @Test
    fun `a list without the search report still lines up with the enum`() {
        val values = ReportType.values()
        val names = ReportType.getReportNames(context, includeSearchResult = false)
        assertThat(names).hasSize(values.size - 2)
        names.forEachIndexed { index, name ->
            assertThat(name)
                .`as`("position $index has to name ${values[index]}")
                .isEqualTo(context.getString(values[index].titleId))
        }
    }

    @Test
    fun `a list with the search report lines up too`() {
        val values = ReportType.values()
        val names = ReportType.getReportNames(context, includeSearchResult = true)
        assertThat(names).hasSize(values.size - 1)
        names.forEachIndexed { index, name ->
            assertThat(name).isEqualTo(context.getString(values[index].titleId))
        }
        assertThat(names).contains(context.getString(R.string.title_search_report))
    }

    @Test
    fun `the search report is the search result fragment`() {
        val fragment = ReportType.SEARCH_RESULT.fragment
        assertThat(fragment).isInstanceOf(SearchResultReportFragment::class.java)
        assertThat(fragment.reportType).isEqualTo(ReportType.SEARCH_RESULT)
    }
}
