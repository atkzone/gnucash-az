package org.gnucash.android.ui.search

import java.math.BigDecimal

/**
 * One search-result row reduced to exactly what the totals / statistics / chart
 * need. Kept as a plain value type (no Android, no cursor) so the aggregation
 * below is a pure function that can be unit-tested without a database.
 */
data class SearchResultRow(
    /** Always a positive magnitude — the transaction's value. */
    val amount: BigDecimal,
    val currencyCode: String,
    /** Fully-qualified account name, or a blank string when it could not be resolved. */
    val accountName: String,
    val tags: List<String>
)

/** Total of all result amounts within one currency. */
data class AmountTotal(val currencyCode: String, val amount: BigDecimal)

/** One bucket of the category breakdown. */
data class CategoryTotal(val label: String, val amount: BigDecimal, val count: Int)

/**
 * Aggregates a search-result set for the "合计 / 分类统计 / 图表" view.
 *
 * Amount semantics (deliberately identical to [SearchSortOrder]'s `ORDER BY`
 * expression): a row's amount is the **sum of the transaction's DEBIT split
 * magnitudes**, i.e. the positive value of the transaction. A signed sum over
 * every split would be zero for a balanced transaction, and the row's
 * signed display balance depends on the account's normal-balance side, so
 * neither is usable as an aggregate.
 */
data class SearchSummary(
    val rowCount: Int,
    val totalsByCurrency: List<AmountTotal>,
    /** Partitioned by account — every row lands in exactly one bucket. */
    val byAccount: List<CategoryTotal>,
    /**
     * Grouped by tag. ⚠️ A transaction carrying several tags is counted in
     * **each** of them, so these buckets can overlap and do not add up to the
     * grand total. Rendered with a note in the UI.
     */
    val byTag: List<CategoryTotal>
) {
    val hasAmounts: Boolean
        get() = totalsByCurrency.any { it.amount.signum() != 0 }

    companion object {
        /** Label used for rows that have no account / no tag. */
        const val UNCATEGORIZED = ""

        fun of(rows: List<SearchResultRow>): SearchSummary {
            val totals = rows
                .groupBy { it.currencyCode }
                .map { (code, group) -> AmountTotal(code, group.fold(BigDecimal.ZERO) { a, r -> a + r.amount }) }
                .sortedWith(compareByDescending<AmountTotal> { it.amount.abs() }.thenBy { it.currencyCode })

            val byAccount = bucketize(rows) { row ->
                listOf(row.accountName.ifBlank { UNCATEGORIZED })
            }
            val byTag = bucketize(rows) { row ->
                row.tags.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(UNCATEGORIZED) }
            }

            return SearchSummary(rows.size, totals, byAccount, byTag)
        }

        private fun bucketize(
            rows: List<SearchResultRow>,
            keys: (SearchResultRow) -> List<String>
        ): List<CategoryTotal> {
            val amounts = LinkedHashMap<String, BigDecimal>()
            val counts = LinkedHashMap<String, Int>()
            for (row in rows) {
                for (key in keys(row)) {
                    amounts[key] = (amounts[key] ?: BigDecimal.ZERO) + row.amount
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
            return amounts.keys
                .map { key -> CategoryTotal(key, amounts.getValue(key), counts.getValue(key)) }
                .sortedWith(compareByDescending<CategoryTotal> { it.amount.abs() }.thenBy { it.label })
        }
    }
}
