package org.gnucash.android.ui.report.search

import com.github.mikephil.charting.data.ChartData
import com.github.mikephil.charting.data.DataSet
import com.github.mikephil.charting.data.Entry
import org.gnucash.android.model.Money

/** One line of the search-result report. */
data class SearchReportRow(
    val dateMillis: Long,
    val description: String,
    val account: String,
    val amount: Money
)

/**
 * Everything the search-result report draws: the matched transactions, plus the totals.
 *
 * Totals are kept **per currency** and are never added together — summing ¥ and $ into one
 * number is meaningless, and a search over a multi-currency book matches both. A search
 * that matched nothing is a valid report (an empty one), which is why [query] is carried
 * along: the screen can say what it searched for instead of looking broken.
 *
 * It extends [ChartData] only because every report here is drawn by a
 * `BaseReportFragment<D : ChartData<*>>` (the balance sheet does exactly the same). The
 * inherited data-set machinery stays empty on purpose: this report is drawn either as a
 * table or as one of three charts from the 图型 selector, so [rows], [totals] and the
 * [SearchChartBucket] lists are what the screen reads — not MPAndroidChart's own data
 * sets, which want a shape ([Entry] with an x index) this report would have to invent.
 */
class SearchReport(
    val rows: List<SearchReportRow>,
    val totals: List<Money>,
    val query: String? = null,
    /**
     * The currency the charts draw, or `null` when there is nothing to chart.
     *
     * One currency and not all of them: a bucket adds money up, and ¥ + $ is a
     * meaningless number. The rows of the other currencies are still in [rows] — they
     * are simply left to the table, and named in [otherCurrencyCodes] so the screen can
     * say so instead of quietly dropping them.
     */
    val chartCurrencyCode: String? = null,
    /** Account buckets of [chartCurrencyCode]. A partition: the slices add up. */
    val byAccount: List<SearchChartBucket> = emptyList(),
    /** Day buckets of [chartCurrencyCode], oldest first — the line chart's time axis. */
    val byDate: List<SearchChartBucket> = emptyList(),
    /** Currencies that were matched but are **not** drawn. Everything but [chartCurrencyCode]. */
    val otherCurrencyCodes: List<String> = emptyList()
) : ChartData<DataSet<Entry>>() {

    val isEmpty: Boolean get() = rows.isEmpty()

    /** Whether the chart 图型 has anything to draw at all. */
    val hasChart: Boolean
        get() = chartCurrencyCode != null && (byAccount.isNotEmpty() || byDate.isNotEmpty())
}
