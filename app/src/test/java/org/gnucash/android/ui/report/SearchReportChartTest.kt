package org.gnucash.android.ui.report

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.model.Commodity
import org.gnucash.android.model.Money
import org.gnucash.android.ui.report.search.SearchReportChart
import org.gnucash.android.ui.report.search.SearchReportRow
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.Test
import java.math.BigDecimal
import java.util.TimeZone

/**
 * The buckets behind the 图型 selector.
 *
 * A wrong bucket is invisible: a pie whose slices do not add up, or a line drawn back to
 * front, still renders and still looks like a chart. That is why the aggregation is a
 * pure function in the first place, and why it is pinned here rather than looked at.
 *
 * Pure JVM — no `GnuCashTest`, no database: the buckets are arithmetic over rows.
 */
class SearchReportChartTest {

    private val usd = Commodity("US Dollars", "USD")
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun `by account is a partition, so the slices add up to the total`() {
        val buckets = SearchReportChart.byAccount(
            listOf(
                row("Expenses:Food", "10.00"),
                row("Expenses:Travel", "25.50"),
                row("Expenses:Food", "4.50")
            )
        )

        // Largest first, so the biggest slice is also the first bar.
        assertThat(buckets.map { it.label })
            .containsExactly("Expenses:Travel", "Expenses:Food")
        // The property the pie chart depends on: no row is dropped and none is counted twice.
        assertThat(buckets.sumOf { it.amount.toBigDecimal() }).isEqualByComparingTo("40.00")
        assertThat(buckets.first { it.label == "Expenses:Food" }.count).isEqualTo(2)
    }

    @Test
    fun `a row with no account lands in the uncategorized bucket`() {
        val buckets = SearchReportChart.byAccount(listOf(row("", "3.00"), row("   ", "0.50")))

        assertThat(buckets).hasSize(1)
        assertThat(buckets.first().label).isEqualTo(SearchReportChart.UNCATEGORIZED)
        assertThat(buckets.first().count).isEqualTo(2)
        assertThat(buckets.first().amount.toBigDecimal()).isEqualByComparingTo("3.50")
    }

    @Test
    fun `an empty result set has no buckets`() {
        assertThat(SearchReportChart.byAccount(emptyList())).isEmpty()
        assertThat(SearchReportChart.byDate(emptyList(), utc)).isEmpty()
    }

    @Test
    fun `by date is oldest first, not biggest first`() {
        // Deliberately arranged so that "sort by amount" would answer the other order: the
        // line chart is read left to right as time passing, and a sawtooth that looks like
        // data is worse than no chart.
        val buckets = SearchReportChart.byDate(
            listOf(
                row("Expenses:Food", "99.00", day(2026, 3, 15)),
                row("Expenses:Food", "0.50", day(2026, 1, 2)),
                row("Expenses:Food", "0.50", day(2026, 1, 2))
            ),
            utc
        )

        assertThat(buckets.map { it.label }).containsExactly("2026-01-02", "2026-03-15")
        assertThat(buckets.map { it.amount.toBigDecimal() })
            .containsExactly(BigDecimal("1.00"), BigDecimal("99.00"))
        assertThat(buckets.map { it.count }).containsExactly(2, 1)
        assertThat(buckets.map { it.sortKey }).isSorted()
        // The axis key is the start of the day, not the instant of some row inside it.
        assertThat(buckets.first().sortKey)
            .isEqualTo(DateTime(2026, 1, 2, 0, 0, DateTimeZone.UTC).millis)
    }

    @Test
    fun `the same instant falls on two different days depending on the zone`() {
        // 23:30 UTC is still the 2nd in UTC and already the 3rd two hours east.
        val instant = DateTime(2026, 1, 2, 23, 30, DateTimeZone.UTC).millis
        val rows = listOf(row("Expenses:Food", "1.00", instant))

        assertThat(SearchReportChart.byDate(rows, TimeZone.getTimeZone("UTC")).map { it.label })
            .containsExactly("2026-01-02")
        assertThat(SearchReportChart.byDate(rows, TimeZone.getTimeZone("Etc/GMT-2")).map { it.label })
            .containsExactly("2026-01-03")
    }

    private fun row(account: String, amount: String, dateMillis: Long = day(2026, 1, 2)) =
        SearchReportRow(
            dateMillis = dateMillis,
            description = "d",
            account = account,
            amount = Money(BigDecimal(amount), usd)
        )

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        DateTime(year, month, dayOfMonth, 12, 0, DateTimeZone.UTC).millis
}
