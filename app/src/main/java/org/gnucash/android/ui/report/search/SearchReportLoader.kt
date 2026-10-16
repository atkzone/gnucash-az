package org.gnucash.android.ui.report.search

import android.content.Context
import android.database.Cursor
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Money
import org.gnucash.android.model.Money.Companion.createZeroInstance
import org.gnucash.android.model.Transaction
import org.gnucash.android.model.TransactionType
import org.gnucash.android.ui.report.ReportsActivity
import timber.log.Timber
import java.math.BigDecimal
import java.util.TimeZone

/**
 * Turns a search WHERE fragment into the rows and totals of the search-result report.
 *
 * Separated from the fragment so the reading can be driven directly by a test: the report
 * is a second consumer of the very same query the result list uses, and the two have to
 * agree on what a "matched transaction" and its "amount" are.
 */
object SearchReportLoader {

    /**
     * @param where the search predicate, or `null` for "no search was run" — which is a
     *              normal state, because 报表 是导航栏里的一个入口，谁都可以从那里打开。
     */
    fun load(
        context: Context,
        where: String? = (context as? ReportsActivity)?.searchWhere,
        transactionsDbAdapter: TransactionsDbAdapter = TransactionsDbAdapter.instance,
        accountsDbAdapter: AccountsDbAdapter = AccountsDbAdapter.instance,
        zone: TimeZone = TimeZone.getDefault()
    ): SearchReport {
        if (where.isNullOrBlank()) return charted(emptyList(), emptyList(), where, zone)

        val rows = ArrayList<SearchReportRow>()
        val totals = LinkedHashMap<String, Money>()
        var cursor: Cursor? = null
        try {
            cursor = transactionsDbAdapter.fetchSearch(where)
            if (cursor == null) return charted(emptyList(), emptyList(), where, zone)
            while (cursor.moveToNext()) {
                val transaction = transactionsDbAdapter.buildModelInstance(cursor)
                val amount = debitTotal(transaction)
                rows.add(
                    SearchReportRow(
                        dateMillis = transaction.datePosted,
                        description = transaction.description,
                        account = accountName(accountsDbAdapter, transaction),
                        amount = amount
                    )
                )
                val currency = amount.commodity.currencyCode
                totals[currency] = totals[currency]?.let { it + amount } ?: amount
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to build the search-result report")
            return charted(rows, totals.values.toList(), where, zone)
        } finally {
            cursor?.close()
        }
        return charted(rows, totals.values.toList(), where, zone)
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

    private fun accountName(accountsDbAdapter: AccountsDbAdapter, transaction: Transaction): String {
        val uid = transaction.defaultAccountUID
        if (uid.isNullOrEmpty()) return ""
        return runCatching { accountsDbAdapter.getFullyQualifiedAccountName(uid) }
            .getOrNull().orEmpty()
    }

    /**
     * The **debit-side magnitude** — the same number the result list shows and the one its
     * ORDER BY sorts by.
     *
     * Splits carry unsigned values and a balanced transaction has equal debit and credit
     * sides, so folding *every* split would always answer zero.
     */
    private fun debitTotal(transaction: Transaction) = transaction.splits
        .filter { it.type == TransactionType.DEBIT }
        .fold(createZeroInstance(transaction.commodity)) { acc, split -> acc + split.value }
}
