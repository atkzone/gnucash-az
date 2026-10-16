package org.gnucash.android.ui.report.search

import org.gnucash.android.model.Money
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import java.util.TimeZone

/**
 * One drawn element of a search-result chart: a pie slice, a bar, or a line point.
 */
data class SearchChartBucket(
    val label: String,
    val amount: Money,
    val count: Int,
    /**
     * The bucket's instant. Only meaningful for the by-date grouping, where it is what
     * the axis is ordered by.
     */
    val sortKey: Long = 0L
)

/**
 * Turns the rows of the search-result report into the shapes the charts need.
 *
 * Pure on purpose — no Android, no cursor, no database — because a wrong bucket is
 * invisible: a pie whose slices do not add up, or a line drawn back to front, still
 * renders and still looks like a chart. Keeping the aggregation here is what lets a
 * JVM test pin it. [SearchReportLoader] only feeds it.
 *
 * ⚠️ Both groupings are meant to be fed the rows of **one** commodity (the loader
 * picks it): a bucket adds money, and adding ¥ to $ is meaningless — the same reason
 * the table's totals are kept per currency and never summed together.
 */
object SearchReportChart {

    /** Rows that resolve to no account land here. Labeled like the summary labels them. */
    const val UNCATEGORIZED = ""

    /**
     * Grouped by account — a genuine **partition**: every row lands in exactly one
     * bucket, so the slices do add up to the total. (The by-tag breakdown does not
     * have that property and is deliberately not charted.)
     *
     * Ordered by amount, largest first, so the biggest slice is also the first bar.
     */
    fun byAccount(rows: List<SearchReportRow>): List<SearchChartBucket> {
        if (rows.isEmpty()) return emptyList()
        val ordered = ArrayList<String>()
        val amounts = LinkedHashMap<String, Money>()
        val counts = HashMap<String, Int>()
        for (row in rows) {
            val key = row.account.ifBlank { UNCATEGORIZED }
            if (!amounts.containsKey(key)) ordered.add(key)
            amounts[key] = amounts[key]?.let { it + row.amount } ?: row.amount
            counts[key] = (counts[key] ?: 0) + 1
        }
        return ordered
            .map { SearchChartBucket(it, amounts.getValue(it), counts.getValue(it) ?: 0) }
            .sortedWith(
                compareByDescending<SearchChartBucket> { it.amount.toBigDecimal().abs() }
                    .thenBy { it.label }
            )
    }

    /**
     * Grouped by calendar day, **oldest first**.
     *
     * A line chart is read left to right as time passing, so the order carries meaning
     * rather than being a presentation detail — sorting by amount here (as [byAccount]
     * does) would draw a sawtooth that looks like data.
     *
     * @param zone the zone the days are cut in. Rows carry instants, and which day an
     *             instant belongs to depends on the zone.
     */
    fun byDate(
        rows: List<SearchReportRow>,
        zone: TimeZone = TimeZone.getDefault()
    ): List<SearchChartBucket> {
        if (rows.isEmpty()) return emptyList()
        val dayZone = DateTimeZone.forTimeZone(zone)
        val amounts = LinkedHashMap<LocalDate, Money>()
        val counts = HashMap<LocalDate, Int>()
        for (row in rows) {
            val day = LocalDate(row.dateMillis, dayZone)
            amounts[day] = amounts[day]?.let { it + row.amount } ?: row.amount
            counts[day] = (counts[day] ?: 0) + 1
        }
        return amounts.keys.sorted().map { day ->
            SearchChartBucket(
                label = day.toString(),
                amount = amounts.getValue(day),
                count = counts.getValue(day) ?: 0,
                sortKey = day.toDateTimeAtStartOfDay(dayZone).millis
            )
        }
    }
}
