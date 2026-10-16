package org.gnucash.android.ui.search

import org.gnucash.android.db.DatabaseSchema.SplitEntry
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.model.TransactionType

/**
 * How the search-result list is ordered.
 *
 * The ORDER BY fragments live here, next to [SearchQueryBuilder], because this
 * package is the single place that owns the search SQL — `TransactionsDbAdapter`
 * only ever receives a ready-made `ORDER BY` string and never guesses one.
 *
 * The alias `t` in the fragments is the transaction alias used by
 * `TransactionsDbAdapter.fetchSearch(where, orderBy)`.
 */
enum class SearchSortOrder {
    DateDesc,
    DateAsc,
    AmountDesc,
    AmountAsc;

    /**
     * Bare `ORDER BY` fragment for the aliased double-join query.
     *
     * ⚠️ Amount is the **sum of the transaction's DEBIT split magnitudes**, not
     * `SUM(value_num)` over every split: split values are stored unsigned and a
     * balanced transaction has equal debit and credit magnitudes, so a signed /
     * naive sum over all splits is always zero and would leave the order
     * undefined. `t._id` is appended as a tie-breaker so the order is stable.
     */
    fun orderBy(): String = when (this) {
        DateDesc -> "t.$DATE_POSTED DESC, t.$NUMBER DESC, t.$ID DESC"
        DateAsc -> "t.$DATE_POSTED ASC, t.$NUMBER ASC, t.$ID ASC"
        AmountDesc -> "$AMOUNT_EXPR DESC, t.$ID DESC"
        AmountAsc -> "$AMOUNT_EXPR ASC, t.$ID ASC"
    }

    companion object {
        private const val DATE_POSTED = TransactionEntry.COLUMN_DATE_POSTED
        private const val NUMBER = TransactionEntry.COLUMN_NUMBER
        private const val ID = TransactionEntry.COLUMN_ID

        /**
         * Debit-side magnitude of a single transaction, expressed as a
         * correlated sub-query so it can be used in `ORDER BY`.
         *
         * Verified against SQLite: `SELECT DISTINCT t.* ... ORDER BY
         * <correlated sub-query>` is accepted (a plain expression would also
         * work, but the sub-query survives the `DISTINCT` projection).
         */
        internal val AMOUNT_EXPR: String =
            "(SELECT COALESCE(SUM(s.${SplitEntry.COLUMN_VALUE_NUM} * 1.0 / s.${SplitEntry.COLUMN_VALUE_DENOM}), 0)" +
                    " FROM ${SplitEntry.TABLE_NAME} s" +
                    " WHERE s.${SplitEntry.COLUMN_TRANSACTION_UID} = t.${TransactionEntry.COLUMN_UID}" +
                    " AND s.${SplitEntry.COLUMN_TYPE} = '${TransactionType.DEBIT.value}')"
    }
}
