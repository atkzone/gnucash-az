package org.gnucash.android.ui.report.search

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.annotation.WorkerThread
import androidx.core.view.isVisible
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import org.gnucash.android.R
import org.gnucash.android.databinding.FragmentSearchReportBinding
import org.gnucash.android.databinding.RowSearchReportBinding
import org.gnucash.android.ui.adapter.DefaultItemSelectedListener
import org.gnucash.android.ui.report.BaseReportFragment
import org.gnucash.android.ui.report.ReportType
import org.gnucash.android.ui.util.displayBalance
import org.gnucash.android.util.formatMediumDate

/**
 * "搜索结果" 报表: the transactions a 全文搜索 / 高级搜索 matched, shown the way the rest of
 * the reports are shown.
 *
 * It exists so the two halves of the app meet: the report screen already had the period
 * and the chart machinery, the search screen already had the query, and neither could use
 * the other's. The query travels in the activity's intent (`ReportsActivity.showSearchResult`),
 * so opening 报表 from the nav bar still lands on the ordinary overview.
 *
 * The account-type and time-range spinners do not apply here — the result set is what it
 * is — and both are hidden rather than left showing values that change nothing.
 *
 * What *is* configurable is the **图型**: the same result set as a table (the totals list,
 * which is the default) or as a pie / bar / line chart. All three charts draw a single
 * currency — the one [SearchReportLoader] picked — and say so when the search also matched
 * others, because a chart that silently drops rows is worse than no chart at all.
 */
class SearchResultReportFragment : BaseReportFragment<SearchReport>() {

    private var binding: FragmentSearchReportBinding? = null

    /** Colour the zero balance is drawn in; borrowed so amounts match the other reports. */
    private var colorBalanceZero: Int = Color.TRANSPARENT

    /**
     * The last generated report. The 图型 selector only changes how it is *drawn*, so
     * switching must not re-run the query.
     */
    private var report: SearchReport? = null

    /** Index into `R.array.search_report_chart_types`; starts on the table. */
    private var chartType: Int = CHART_TYPE_TABLE

    override val reportType: ReportType = ReportType.SEARCH_RESULT

    override fun requiresAccountTypeOptions(): Boolean = false

    override fun requiresTimeRangeOptions(): Boolean = false

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // No menu: nothing on this screen is configurable.
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View {
        val binding = FragmentSearchReportBinding.inflate(inflater, container, false)
        this.binding = binding
        colorBalanceZero = binding.searchReportCount.currentTextColor

        val adapter = ArrayAdapter.createFromResource(
            binding.root.context,
            R.array.search_report_chart_types,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.searchReportChartType.adapter = adapter
        binding.searchReportChartType.onItemSelectedListener =
            DefaultItemSelectedListener { _, _, position, _ ->
                if (chartType != position) {
                    chartType = position
                    render()
                }
            }
        return binding.root
    }

    @WorkerThread
    override fun generateReport(context: Context): SearchReport = SearchReportLoader.load(context)

    override fun displayReport(data: SearchReport) {
        report = data
        render()
    }

    /**
     * Draws the report the way the 图型 selector asks for.
     *
     * The table and the chart panel share one container and one height, and exactly one of
     * them is visible: swapping 图型 must not make the screen jump.
     */
    private fun render() {
        val binding = binding ?: return
        val data = report ?: return
        val table = chartType == CHART_TYPE_TABLE
        // "Nothing here" means two different things: the search matched nothing at all, or
        // it matched rows that no chart can draw. Say which one it is.
        val chartEmpty = !table && !data.hasChart

        binding.searchReportTableScroll.isVisible = table
        binding.searchReportChartPanel.isVisible = !table && !chartEmpty
        binding.emptySearchReport.isVisible = if (table) data.isEmpty else chartEmpty
        binding.emptySearchReport.setText(
            if (table) R.string.label_search_report_empty else R.string.search_report_chart_empty
        )
        binding.searchReportCount.text =
            getString(R.string.label_search_report_count, data.rows.size)

        if (table) renderTable(binding, data) else renderChart(binding, data)
    }

    private fun renderTable(binding: FragmentSearchReportBinding, data: SearchReport) {
        // Row 0 is the header, which stays: rebuilding it would let the column widths
        // jump between refreshes.
        val extras = binding.tableSearchReport.childCount - 1
        if (extras > 0) binding.tableSearchReport.removeViews(1, extras)

        val context = binding.root.context
        val inflater = LayoutInflater.from(context)
        for (row in data.rows) {
            val rowBinding = RowSearchReportBinding.inflate(inflater, binding.tableSearchReport, true)
            rowBinding.reportDate.text = formatMediumDate(row.dateMillis)
            rowBinding.reportDescription.text = row.description
            rowBinding.reportAccount.text = row.account
            rowBinding.reportAmount.displayBalance(row.amount, colorBalanceZero)
        }
        for (total in data.totals) {
            val rowBinding = RowSearchReportBinding.inflate(inflater, binding.tableSearchReport, true)
            rowBinding.reportDate.text = getString(R.string.label_search_report_total)
            rowBinding.reportDescription.text = ""
            rowBinding.reportAccount.text = total.commodity.currencyCode
            rowBinding.reportAmount.displayBalance(total, colorBalanceZero)
        }
    }

    private fun renderChart(binding: FragmentSearchReportBinding, data: SearchReport) {
        val context = binding.root.context
        val byDate = chartType == CHART_TYPE_LINE

        binding.searchReportChartNote.isVisible = data.otherCurrencyCodes.isNotEmpty()
        if (data.otherCurrencyCodes.isNotEmpty()) {
            binding.searchReportChartNote.text = getString(
                R.string.search_report_chart_note,
                data.chartCurrencyCode.orEmpty(),
                data.otherCurrencyCodes.joinToString(", ")
            )
        }

        val buckets = if (byDate) data.byDate else data.byAccount
        // A zero slice is invisible and a zero bar is a gap; a line keeps its zeros,
        // because there they are points on a real time axis rather than "nothing".
        val drawn = if (byDate) buckets else buckets.filter { !it.amount.isAmountZero }

        binding.searchReportChartSlice.text = getString(
            if (byDate) {
                R.string.search_report_chart_by_date
            } else {
                R.string.search_report_chart_by_account
            },
            data.chartCurrencyCode.orEmpty()
        )

        val chart = when (chartType) {
            CHART_TYPE_PIE -> buildPieChart(context, drawn)
            CHART_TYPE_BAR -> buildBarChart(context, drawn)
            else -> buildLineChart(context, buckets)
        }
        binding.searchReportChartContainer.removeAllViews()
        binding.searchReportChartContainer.addView(
            chart,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun buildPieChart(context: Context, buckets: List<SearchChartBucket>): PieChart {
        val entries = buckets.map { bucket ->
            PieEntry(bucket.amount.abs().toFloat(), label(context, bucket.label))
        }
        val dataSet = PieDataSet(entries, "").apply {
            colors = PALETTE
            setSliceSpace(2f)
            valueTextColor = Color.WHITE
        }
        return PieChart(context).apply {
            description.isEnabled = false
            isDrawHoleEnabled = false
            setUsePercentValues(true)
            legend.isWordWrapEnabled = true
            legend.textColor = getTextColor(context)
            setEntryLabelColor(getTextColor(context))
            data = PieData(dataSet)
            animateY(ANIMATION_DURATION)
            invalidate()
        }
    }

    private fun buildBarChart(context: Context, buckets: List<SearchChartBucket>): BarChart {
        val entries = buckets.mapIndexed { index, bucket ->
            BarEntry(index.toFloat(), bucket.amount.toFloat())
        }
        val dataSet = BarDataSet(entries, "").apply {
            colors = buckets.indices.map { PALETTE[it % PALETTE.size] }
            valueTextColor = getTextColor(context)
        }
        return BarChart(context).apply {
            description.isEnabled = false
            setOnChartValueSelectedListener(this@SearchResultReportFragment)
            axisLeft.textColor = getTextColor(context)
            axisLeft.enableGridDashedLine(4.0f, 4.0f, 0f)
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.valueFormatter = IndexAxisValueFormatter(buckets.map { label(context, it.label) })
            xAxis.granularity = 1f
            // Account names are long; without this the labels collide into a smear.
            xAxis.labelRotationAngle = -30f
            xAxis.setDrawGridLines(false)
            xAxis.textColor = getTextColor(context)
            legend.isEnabled = false
            data = BarData(dataSet)
            animateY(ANIMATION_DURATION)
            invalidate()
        }
    }

    private fun buildLineChart(context: Context, buckets: List<SearchChartBucket>): LineChart {
        val entries = buckets.mapIndexed { index, bucket ->
            Entry(index.toFloat(), bucket.amount.toFloat())
        }
        val dataSet = LineDataSet(entries, "").apply {
            color = PALETTE[0]
            lineWidth = 2f
            setDrawValues(false)
            setDrawCircleHole(false)
        }
        return LineChart(context).apply {
            description.isEnabled = false
            setOnChartValueSelectedListener(this@SearchResultReportFragment)
            axisLeft.textColor = getTextColor(context)
            axisLeft.enableGridDashedLine(4.0f, 4.0f, 0f)
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.valueFormatter = IndexAxisValueFormatter(buckets.map { it.label })
            xAxis.granularity = 1f
            xAxis.labelRotationAngle = -30f
            xAxis.setDrawGridLines(false)
            xAxis.textColor = getTextColor(context)
            legend.isEnabled = false
            data = LineData(dataSet)
            animateX(ANIMATION_DURATION)
            invalidate()
        }
    }

    /** The summary view labels an account-less bucket "(none)"; keep the two consistent. */
    private fun label(context: Context, bucketLabel: String): String =
        if (bucketLabel == SearchReportChart.UNCATEGORIZED) {
            context.getString(R.string.search_summary_no_category)
        } else {
            bucketLabel
        }

    companion object {
        /** Must match the order of `R.array.search_report_chart_types`. */
        private const val CHART_TYPE_TABLE = 0
        private const val CHART_TYPE_PIE = 1
        private const val CHART_TYPE_BAR = 2
        private const val CHART_TYPE_LINE = 3

        private const val ANIMATION_DURATION = 600

        /**
         * Slice / bar colours, cycled when there are more buckets than colours.
         *
         * A local palette rather than the base class's `COLORS`: that one is `protected`
         * on its companion, and reading it from another file makes the Kotlin 2.4 backend
         * fail outright (`SyntheticAccessorLowering should not attempt to modify other
         * files!`). Every other report fragment here carries its own palette for the same
         * reason.
         */
        private val PALETTE: List<Int> = listOf(
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#E91E63"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#FFC107"),
            Color.parseColor("#795548")
        )
    }
}
