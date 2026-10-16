package org.gnucash.android.ui.report

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.CommoditiesDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Account
import org.gnucash.android.model.Commodity
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.report.search.SearchReportLoader
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.TimeZone

/**
 * The search-result report reads the **same** query the result list does, so the two
 * have to agree on what a matched transaction and its amount are.
 *
 * Two of these are the ones that would go unnoticed: the amount is the **debit
 * magnitude** (folding every split would answer zero on a balanced transaction), and
 * totals stay per-currency (adding ¥ and $ together is meaningless). Both are driven
 * against a real database here, because the amount comes out of the same
 * `fetchSearch` cursor the list uses.
 */
class SearchReportLoaderTest : GnuCashTest() {

    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var transactionsDbAdapter: TransactionsDbAdapter
    private lateinit var commoditiesDbAdapter: CommoditiesDbAdapter

    private lateinit var debitAccount: Account
    private lateinit var creditAccount: Account

    private lateinit var lunch: Transaction
    private lateinit var taxi: Transaction
    private lateinit var movie: Transaction

    @Before
    fun setUp() {
        accountsDbAdapter = AccountsDbAdapter.instance
        transactionsDbAdapter = TransactionsDbAdapter.instance
        commoditiesDbAdapter = CommoditiesDbAdapter.instance

        debitAccount = Account("Assets:Wallet")
        creditAccount = Account("Expenses:Food")
        accountsDbAdapter.addRecord(debitAccount)
        accountsDbAdapter.addRecord(creditAccount)

        lunch = save("午饭", BigDecimal("10.00"), 1_700_000_000_000L)
        taxi = save("打车", BigDecimal("25.50"), 1_700_100_000_000L)
        movie = save("看电影", BigDecimal("40.00"), 1_700_200_000_000L)
    }

    @After
    fun tearDown() {
        transactionsDbAdapter.deleteAllRecords()
        accountsDbAdapter.deleteAllRecords()
    }

    @Test
    fun `a null where is an empty report that still names the query`() {
        val report = SearchReportLoader.load(context, null, transactionsDbAdapter, accountsDbAdapter)

        assertThat(report.isEmpty).isTrue()
        assertThat(report.totals).isEmpty()
        assertThat(report.query).isNull()
    }

    @Test
    fun `a blank where is a no-op, not a query for everything`() {
        val report = SearchReportLoader.load(context, "   ", transactionsDbAdapter, accountsDbAdapter)

        assertThat(report.isEmpty).isTrue()
        assertThat(report.rows).isEmpty()
    }

    @Test
    fun `a matching where lists the rows with their date, description and account`() {
        val report = loadLike("午饭")

        assertThat(report.rows).hasSize(1)
        val row = report.rows.first()
        assertThat(row.description).isEqualTo("午饭")
        assertThat(row.dateMillis).isEqualTo(lunch.datePosted)
        assertThat(row.account).isEqualTo(
            accountsDbAdapter.getFullyQualifiedAccountName(debitAccount.uid)
        )
    }

    @Test
    fun `a where that matches nothing is a valid empty report`() {
        val report = loadLike("不存在的描述")

        assertThat(report.isEmpty).isTrue()
        assertThat(report.query).isNotBlank()
    }

    @Test
    fun `the amount is the debit magnitude rather than zero`() {
        val report = loadLike("打车")

        // Folding every split would cancel to zero: a balanced transaction has equal
        // debit and credit sides, and split values are unsigned.
        assertThat(report.rows.first().amount.toBigDecimal()).isEqualByComparingTo("25.50")
    }

    @Test
    fun `totals add the matched rows together`() {
        val report = loadLike("#none")   // matches nothing -> empty baseline
        assertThat(report.totals).isEmpty()

        val all = SearchReportLoader.load(
            context,
            "1",  // every transaction
            transactionsDbAdapter,
            accountsDbAdapter
        )
        assertThat(all.rows).hasSize(3)
        assertThat(all.totals).hasSize(1)
        assertThat(all.totals.first().toBigDecimal()).isEqualByComparingTo("75.50")
        assertThat(all.totals.first().commodity.currencyCode)
            .isEqualTo(debitAccount.commodity.currencyCode)
    }

    @Test
    fun `totals stay per currency and are never added across them`() {
        val home = debitAccount.commodity.currencyCode
        val eur = commoditiesDbAdapter.getCurrency("EUR")!!
        // Premise guard: if "EUR" resolved to the book's own currency the per-currency
        // property would pass without ever being exercised.
        assertThat(eur.currencyCode).isNotEqualTo(home)

        val eurDebit = Account("Assets:EuroWallet").apply { commodity = eur }
        val eurCredit = Account("Expenses:Travel").apply { commodity = eur }
        accountsDbAdapter.addRecord(eurDebit)
        accountsDbAdapter.addRecord(eurCredit)
        saveIn("机票", BigDecimal("100.00"), eurDebit, eurCredit, eur)

        val report = SearchReportLoader.load(
            context, "1", transactionsDbAdapter, accountsDbAdapter
        )

        assertThat(report.totals.map { it.commodity.currencyCode })
            .containsExactlyInAnyOrder(home, eur.currencyCode)
        // The home-currency total must not have absorbed the €100.
        val homeTotal = report.totals.first { it.commodity.currencyCode == home }
        assertThat(homeTotal.toBigDecimal()).isEqualByComparingTo("75.50")
    }

    @Test
    fun `the chart draws one currency and leaves the others to the table`() {
        val home = debitAccount.commodity.currencyCode
        val eur = commoditiesDbAdapter.getCurrency("EUR")!!
        assertThat(eur.currencyCode).isNotEqualTo(home)
        val eurDebit = Account("Assets:EuroWallet").apply { commodity = eur }
        val eurCredit = Account("Expenses:Travel").apply { commodity = eur }
        accountsDbAdapter.addRecord(eurDebit)
        accountsDbAdapter.addRecord(eurCredit)
        saveIn("机票", BigDecimal("100.00"), eurDebit, eurCredit, eur)

        val report = SearchReportLoader.load(context, "1", transactionsDbAdapter, accountsDbAdapter)

        // €100 beats the home currency's 75.50, so the chart follows the money that was
        // actually searched for instead of the book's default currency. The home rows are
        // not dropped from the report — they are left to the table and named here, because
        // a chart that silently ignores part of the result set is worse than no chart.
        assertThat(report.chartCurrencyCode).isEqualTo(eur.currencyCode)
        assertThat(report.otherCurrencyCodes).containsExactly(home)
        assertThat(report.byAccount.map { it.amount.toBigDecimal() })
            .containsExactly(BigDecimal("100.00"))
        assertThat(report.hasChart).isTrue()
    }

    @Test
    fun `every matched row of the charted currency is in exactly one account bucket`() {
        val report = SearchReportLoader.load(context, "1", transactionsDbAdapter, accountsDbAdapter)

        assertThat(report.chartCurrencyCode).isEqualTo(debitAccount.commodity.currencyCode)
        assertThat(report.otherCurrencyCodes).isEmpty()
        assertThat(report.byAccount.map { it.count }.sum()).isEqualTo(3)
        assertThat(report.byAccount.sumOf { it.amount.toBigDecimal() })
            .isEqualByComparingTo("75.50")
    }

    @Test
    fun `the day buckets are days, oldest first, and cover every row`() {
        val zone = TimeZone.getTimeZone("UTC")
        val report = SearchReportLoader.load(
            context, "1", transactionsDbAdapter, accountsDbAdapter, zone
        )

        // Computed independently of the loader, with the same zone handed to both.
        val expectedDays = listOf(lunch, taxi, movie)
            .map { LocalDate(it.datePosted, DateTimeZone.UTC).toString() }
            .sorted()
        assertThat(report.byDate.map { it.label }).isEqualTo(expectedDays)
        assertThat(report.byDate.map { it.sortKey }).isSorted()
        assertThat(report.byDate.map { it.count }.sum()).isEqualTo(3)
    }

    @Test
    fun `a report with nothing in it has no chart`() {
        val report = loadLike("不存在的描述")

        assertThat(report.hasChart).isFalse()
        assertThat(report.chartCurrencyCode).isNull()
        assertThat(report.byAccount).isEmpty()
        assertThat(report.byDate).isEmpty()
    }

    // The "account cannot be named" guard in `SearchReportLoader.accountName` cannot be
    // reached from here: `splits.account_uid` is NOT NULL with a FOREIGN KEY onto
    // `accounts`, so a split can never point at an account that is not in the book. The
    // guard is pinned at the source level in SearchReportWiringTest instead.

    // ------------------------------------------------------------------ helpers

    private fun loadLike(fragment: String) = SearchReportLoader.load(
        context,
        "(t.${TransactionEntry.COLUMN_DESCRIPTION} LIKE '%$fragment%')",
        transactionsDbAdapter,
        accountsDbAdapter
    )

    private fun save(description: String, amount: BigDecimal, datePosted: Long): Transaction =
        saveIn(description, amount, debitAccount, creditAccount, debitAccount.commodity, datePosted)

    private fun saveIn(
        description: String,
        amount: BigDecimal,
        debit: Account,
        credit: Account,
        commodity: Commodity,
        datePosted: Long = 1_700_000_000_000L
    ): Transaction {
        val transaction = Transaction(description)
        transaction.datePosted = datePosted
        val split = Split(Money(amount, commodity), debit)
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(credit))
        transactionsDbAdapter.addRecord(transaction)
        return transaction
    }
}
