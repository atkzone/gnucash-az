package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.math.BigDecimal

/**
 * Pure-logic tests for the search-result aggregation (no DB, no Android).
 *
 * The three properties worth pinning are: totals are per-currency, the account
 * breakdown is a partition (sums to the grand total), and the tag breakdown is
 * explicitly allowed to overlap.
 */
class SearchSummaryTest {

    private fun row(
        amount: String,
        account: String = "Expenses:Food",
        currency: String = "CNY",
        tags: List<String> = emptyList()
    ) = SearchResultRow(BigDecimal(amount), currency, account, tags)

    @Test
    fun `empty result set summarises to nothing`() {
        val summary = SearchSummary.of(emptyList())

        assertThat(summary.rowCount).isEqualTo(0)
        assertThat(summary.totalsByCurrency).isEmpty()
        assertThat(summary.byAccount).isEmpty()
        assertThat(summary.byTag).isEmpty()
        assertThat(summary.hasAmounts).isFalse()
    }

    @Test
    fun `totals are grouped by currency`() {
        val summary = SearchSummary.of(
            listOf(
                row("10.00"),
                row("5.50"),
                row("7.00", currency = "USD")
            )
        )

        assertThat(summary.totalsByCurrency)
            .containsExactly(
                AmountTotal("CNY", BigDecimal("15.50")),
                AmountTotal("USD", BigDecimal("7.00"))
            )
    }

    @Test
    fun `currencies are ordered by absolute amount`() {
        val summary = SearchSummary.of(
            listOf(row("3.00"), row("100.00", currency = "USD"))
        )

        assertThat(summary.totalsByCurrency.map { it.currencyCode }).containsExactly("USD", "CNY")
    }

    @Test
    fun `account breakdown partitions every row into exactly one bucket`() {
        val summary = SearchSummary.of(
            listOf(
                row("10.00", account = "Expenses:Food"),
                row("2.50", account = "Expenses:Food"),
                row("4.00", account = "Assets:Checking")
            )
        )

        assertThat(summary.byAccount)
            .containsExactly(
                CategoryTotal("Expenses:Food", BigDecimal("12.50"), 2),
                CategoryTotal("Assets:Checking", BigDecimal("4.00"), 1)
            )
        // The partition property: buckets add up to the grand total.
        val bucketSum = summary.byAccount.fold(BigDecimal.ZERO) { a, c -> a + c.amount }
        assertThat(bucketSum).isEqualByComparingTo("16.50")
    }

    @Test
    fun `a row without an account lands in the uncategorized bucket`() {
        val summary = SearchSummary.of(listOf(row("1.00", account = "")))

        assertThat(summary.byAccount).containsExactly(
            CategoryTotal(SearchSummary.UNCATEGORIZED, BigDecimal("1.00"), 1)
        )
    }

    @Test
    fun `tag breakdown counts a multi-tagged row in each bucket`() {
        val summary = SearchSummary.of(
            listOf(
                row("10.00", account = "A", tags = listOf("餐饮", "报销")),
                row("5.00", account = "B", tags = listOf("餐饮"))
            )
        )

        assertThat(summary.byTag)
            .containsExactly(
                CategoryTotal("餐饮", BigDecimal("15.00"), 2),
                CategoryTotal("报销", BigDecimal("10.00"), 1)
            )
        // Documented overlap: buckets do NOT have to add up to the total.
        val bucketSum = summary.byTag.fold(BigDecimal.ZERO) { a, c -> a + c.amount }
        assertThat(bucketSum).isEqualByComparingTo("25.00")
    }

    @Test
    fun `an untagged row lands in the uncategorized tag bucket`() {
        val summary = SearchSummary.of(listOf(row("3.00", account = "A")))

        assertThat(summary.byTag).containsExactly(
            CategoryTotal(SearchSummary.UNCATEGORIZED, BigDecimal("3.00"), 1)
        )
    }

    @Test
    fun `blank tags are ignored instead of creating an empty bucket`() {
        val summary = SearchSummary.of(
            listOf(row("3.00", account = "A", tags = listOf("  ", "餐饮")))
        )

        assertThat(summary.byTag.map { it.label }).containsExactly("餐饮")
    }

    @Test
    fun `buckets are ordered by absolute amount then label`() {
        val summary = SearchSummary.of(
            listOf(
                row("1.00", account = "B"),
                row("1.00", account = "A"),
                row("9.00", account = "C")
            )
        )

        assertThat(summary.byAccount.map { it.label }).containsExactly("C", "A", "B")
    }

    @Test
    fun `all zero amounts report no amounts`() {
        val summary = SearchSummary.of(listOf(row("0.00", account = "A")))

        assertThat(summary.rowCount).isEqualTo(1)
        assertThat(summary.hasAmounts).isFalse()
    }
}
