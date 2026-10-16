package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Pins the ORDER BY fragments used by `TransactionsDbAdapter.fetchSearch(where, orderBy)`.
 *
 * These are asserted as strings on purpose: the adapter never builds them, and
 * the interesting facts are (a) dates use the transaction alias, and (b) the
 * amount ordering keys off the **DEBIT** side rather than a naive sum over all
 * splits (which is always zero for a balanced transaction).
 */
class SearchSortOrderTest {

    @Test
    fun `date orders use the timestamp column with the transaction alias`() {
        assertThat(SearchSortOrder.DateDesc.orderBy())
            .startsWith("t.timestamp DESC")
            .contains("t.num DESC")
        assertThat(SearchSortOrder.DateAsc.orderBy())
            .startsWith("t.timestamp ASC")
            .contains("t.num ASC")
    }

    @Test
    fun `every order is made stable with an _id tie-breaker`() {
        for (order in SearchSortOrder.entries) {
            assertThat(order.orderBy()).endsWith("t._id " + if (order.name.endsWith("Asc")) "ASC" else "DESC")
        }
    }

    @Test
    fun `amount orders key off the debit side of the split`() {
        val expression = SearchSortOrder.AMOUNT_EXPR

        assertThat(expression)
            .contains("FROM splits s")
            .contains("s.transaction_uid = t.uid")
            .contains("s.type = 'DEBIT'")
            .contains("SUM(s.value_num * 1.0 / s.value_denom)")
        // Guard against a regression to the naive signed sum.
        assertThat(expression).doesNotContain("s1.")
    }

    @Test
    fun `amount descending is expressed descending on the debit magnitude`() {
        val sql = SearchSortOrder.AmountDesc.orderBy()

        assertThat(sql).startsWith(SearchSortOrder.AMOUNT_EXPR + " DESC")
        assertThat(sql).endsWith("t._id DESC")
    }

    @Test
    fun `amount ascending is expressed ascending on the debit magnitude`() {
        val sql = SearchSortOrder.AmountAsc.orderBy()

        assertThat(sql).startsWith(SearchSortOrder.AMOUNT_EXPR + " ASC")
        assertThat(sql).endsWith("t._id ASC")
    }

    @Test
    fun `each order produces a distinct fragment besides the shared shape`() {
        val fragments = SearchSortOrder.entries.map { it.orderBy() }
        assertThat(fragments).doesNotHaveDuplicates()
    }
}
