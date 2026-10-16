package org.gnucash.android.test.unit.db

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Account
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.search.SearchSortOrder
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * The ①d pay-off for sorting: the ORDER BY fragments produced by
 * [SearchSortOrder] really reorder real rows when handed to
 * `TransactionsDbAdapter.fetchSearch(where, orderBy)`.
 *
 * The three amounts (100 / 250 / 500) are deliberately in the opposite order to
 * the three dates (3000 / 2000 / 1000), so a test cannot pass by accident when
 * date and amount happen to agree.
 */
class SearchResultSortIntegrationTest : GnuCashTest() {
    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var transactionsDbAdapter: TransactionsDbAdapter
    private lateinit var debitAccount: Account
    private lateinit var creditAccount: Account

    private lateinit var small: Transaction   // 100, newest
    private lateinit var medium: Transaction  // 250, middle
    private lateinit var large: Transaction   // 500, oldest

    @Before
    fun setUp() {
        accountsDbAdapter = AccountsDbAdapter.instance
        transactionsDbAdapter = TransactionsDbAdapter.instance

        debitAccount = Account("Sort debit")
        creditAccount = Account("Sort credit")
        accountsDbAdapter.addRecord(debitAccount)
        accountsDbAdapter.addRecord(creditAccount)

        small = save("small", BigDecimal("100"), 3_000L)
        medium = save("medium", BigDecimal("250"), 2_000L)
        large = save("large", BigDecimal("500"), 1_000L)
    }

    @After
    fun tearDown() {
        transactionsDbAdapter.deleteAllRecords()
        accountsDbAdapter.deleteAllRecords()
    }

    @Test
    fun `date ascending walks forwards in time`() {
        assertThat(uids(SearchSortOrder.DateAsc))
            .containsExactly(large.uid, medium.uid, small.uid)
    }

    @Test
    fun `date descending walks backwards in time`() {
        assertThat(uids(SearchSortOrder.DateDesc))
            .containsExactly(small.uid, medium.uid, large.uid)
    }

    @Test
    fun `amount ascending orders by the debit magnitude`() {
        assertThat(uids(SearchSortOrder.AmountAsc))
            .containsExactly(small.uid, medium.uid, large.uid)
    }

    @Test
    fun `amount descending orders by the debit magnitude`() {
        assertThat(uids(SearchSortOrder.AmountDesc))
            .containsExactly(large.uid, medium.uid, small.uid)
    }

    @Test
    fun `a custom order works with an empty where clause`() {
        // Exercises the `AND 1` branch of fetchSearch(where, orderBy): an empty
        // WHERE must still return every transaction, just sorted.
        val cursor = transactionsDbAdapter.fetchSearch("", SearchSortOrder.AmountDesc.orderBy())
        val found = read(cursor)
        assertThat(found).containsExactly(large.uid, medium.uid, small.uid)
    }

    @Test
    fun `the default ordering equals the explicit date descending order`() {
        val default = read(transactionsDbAdapter.fetchSearch(""))
        assertThat(default).containsExactly(small.uid, medium.uid, large.uid)
    }

    @Test
    fun `sorting does not change which rows match a filter`() {
        val where = "t.${TransactionEntry.COLUMN_DESCRIPTION} IN ('small', 'large')"
        val asc = read(transactionsDbAdapter.fetchSearch(where, SearchSortOrder.AmountAsc.orderBy()))
        val desc = read(transactionsDbAdapter.fetchSearch(where, SearchSortOrder.AmountDesc.orderBy()))

        // Same set of rows either way...
        assertThat(asc).containsExactlyInAnyOrder(*desc.toTypedArray())
        // ...but in opposite orders, with the excluded row still excluded.
        assertThat(asc).containsExactly(small.uid, large.uid)
        assertThat(desc).containsExactly(large.uid, small.uid)
    }

    private fun uids(order: SearchSortOrder): List<String> =
        read(transactionsDbAdapter.fetchSearch("", order.orderBy()))

    private fun read(cursor: android.database.Cursor?): List<String> {
        if (cursor == null) return emptyList()
        val out = mutableListOf<String>()
        val index = cursor.getColumnIndex(TransactionEntry.COLUMN_UID)
            .takeIf { it >= 0 }
            ?: cursor.getColumnIndexOrThrow("t." + TransactionEntry.COLUMN_UID)
        cursor.use {
            while (it.moveToNext()) {
                out.add(it.getString(index))
            }
        }
        return out
    }

    private fun save(description: String, amount: BigDecimal, datePosted: Long): Transaction {
        val transaction = Transaction(description)
        transaction.datePosted = datePosted
        val split = Split(Money(amount, debitAccount.commodity), debitAccount)
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(creditAccount))
        transactionsDbAdapter.addRecord(transaction)
        return transaction
    }
}
