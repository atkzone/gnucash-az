/*
 * Copyright (c) 2015 Ngewi Fet <ngewif@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.ui.report

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import org.gnucash.android.R
import org.gnucash.android.ui.report.barchart.StackedBarChartFragment
import org.gnucash.android.ui.report.linechart.CashFlowLineChartFragment
import org.gnucash.android.ui.report.piechart.PieChartFragment
import org.gnucash.android.ui.report.search.SearchResultReportFragment
import org.gnucash.android.ui.report.sheet.BalanceSheetFragment

/**
 * Different types of reports
 *
 * This class also contains mappings for the reports of the different types which are available
 * in the system. When adding a new report, make sure to add a mapping in the constructor
 */
enum class ReportType(
    @StringRes val titleId: Int,
    @ColorRes val colorId: Int,
    val fragmentClass: Class<out BaseReportFragment<*>>
) {
    PIE_CHART(
        R.string.title_pie_chart,
        R.color.report_orange,
        PieChartFragment::class.java
    ),
    BAR_CHART(
        R.string.title_bar_chart,
        R.color.report_red,
        StackedBarChartFragment::class.java
    ),
    LINE_CHART(
        R.string.title_line_chart,
        R.color.report_blue,
        CashFlowLineChartFragment::class.java
    ),
    SHEET(
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

    val fragment: BaseReportFragment<*>
        get() = fragmentClass.newInstance()

    companion object {
        private val _values = values()

        /**
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
    }
}
