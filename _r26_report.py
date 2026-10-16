"""R26 需求③：把搜索结果报表变成「可按所选图型 / 表格展示」。

- `SearchReport` 携带图表数据（图型只画**一个**币种，其余币种只出现在表格里）；
- `SearchReportLoader` 负责挑币种并算好桶（纯函数在 `SearchReportChart`）。
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
    for old, new, expected in edits:
        n = text.count(old)
        if n != expected:
            raise SystemExit(
                "anchor count %d (want %d) for %r in %s" % (n, expected, old[:70], rel)
            )
        text = text.replace(old, new, expected)
    out = text.replace("\n", "\r\n") if crlf else text
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("OK", rel, "edits=", len(edits), "crlf=", crlf, "bytes=", len(out))


# ------------------------------------------------------------------ SearchReport
patch("app/src/main/java/org/gnucash/android/ui/report/search/SearchReport.kt", [(
    """class SearchReport(
    val rows: List<SearchReportRow>,
    val totals: List<Money>,
    val query: String? = null
) : ChartData<DataSet<Entry>>() {

    val isEmpty: Boolean get() = rows.isEmpty()
}
""",
    """class SearchReport(
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
""",
    1,
)])

# -------------------------------------------------------------- SearchReportLoader
patch("app/src/main/java/org/gnucash/android/ui/report/search/SearchReportLoader.kt", [
    # imports
    ("""import org.gnucash.android.ui.report.ReportsActivity
import timber.log.Timber
""",
     """import org.gnucash.android.ui.report.ReportsActivity
import timber.log.Timber
import java.math.BigDecimal
import java.util.TimeZone
""",
     1),
    # signature
    ("""        transactionsDbAdapter: TransactionsDbAdapter = TransactionsDbAdapter.instance,
        accountsDbAdapter: AccountsDbAdapter = AccountsDbAdapter.instance
    ): SearchReport {
        if (where.isNullOrBlank()) return SearchReport(emptyList(), emptyList(), where)
""",
     """        transactionsDbAdapter: TransactionsDbAdapter = TransactionsDbAdapter.instance,
        accountsDbAdapter: AccountsDbAdapter = AccountsDbAdapter.instance,
        zone: TimeZone = TimeZone.getDefault()
    ): SearchReport {
        if (where.isNullOrBlank()) return charted(emptyList(), emptyList(), where, zone)
""",
     1),
    # early exit inside the try
    ("""            if (cursor == null) return SearchReport(emptyList(), emptyList(), where)
""",
     """            if (cursor == null) return charted(emptyList(), emptyList(), where, zone)
""",
     1),
    # catch
    ("""            Timber.e(e, "Failed to build the search-result report")
            return SearchReport(rows, totals.values.toList(), where)
""",
     """            Timber.e(e, "Failed to build the search-result report")
            return charted(rows, totals.values.toList(), where, zone)
""",
     1),
    # normal return
    ("""        return SearchReport(rows, totals.values.toList(), where)
    }
""",
     """        return charted(rows, totals.values.toList(), where, zone)
    }

    /**
     * Builds the report, including the chart data for **one** currency.
     *
     * The charted currency is the one with the largest absolute total, not the book's
     * default: a search can easily match nothing but foreign-currency rows (a trip's
     * expenses), and charting the default there would draw an empty chart over a full
     * result set.
     */
    private fun charted(
        rows: List<SearchReportRow>,
        totals: List<Money>,
        where: String?,
        zone: TimeZone
    ): SearchReport {
        val currency = primaryCurrency(rows)
        val chartedRows = rows.filter { it.amount.commodity.currencyCode == currency }
        return SearchReport(
            rows = rows,
            totals = totals,
            query = where,
            chartCurrencyCode = currency,
            byAccount = SearchReportChart.byAccount(chartedRows),
            byDate = SearchReportChart.byDate(chartedRows, zone),
            otherCurrencyCodes = totals.map { it.commodity.currencyCode }
                .filter { it != currency }
        )
    }

    /**
     * @return the currency of the largest absolute total, or `null` for an empty report.
     *
     * `maxByOrNull` answers the **first** maximum and the map follows row order, so the
     * choice is deterministic — a tie cannot flip the chart between two openings.
     */
    private fun primaryCurrency(rows: List<SearchReportRow>): String? {
        if (rows.isEmpty()) return null
        val magnitudes = LinkedHashMap<String, BigDecimal>()
        for (row in rows) {
            val code = row.amount.commodity.currencyCode
            magnitudes[code] =
                (magnitudes[code] ?: BigDecimal.ZERO) + row.amount.toBigDecimal().abs()
        }
        return magnitudes.maxByOrNull { it.value }?.key
    }
""",
     1),
])
