package org.gnucash.android.ui.search

import android.database.DatabaseUtils.sqlEscapeString
import org.gnucash.android.db.DatabaseHelper.Companion.sqlEscapeLike
import org.gnucash.android.db.DatabaseSchema.AccountEntry
import org.gnucash.android.db.DatabaseSchema.SplitEntry
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.model.TransactionType
import org.gnucash.android.util.toDateTimeAtEndOfDay
import org.gnucash.android.util.toMillis
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

/**
 * Single point where a [SearchCriteria] list is turned into a bare SQL `WHERE`
 * fragment. Extracted from [SearchForm] so the boolean semantics have exactly
 * one implementation.
 *
 * Boolean layers (kept deliberately independent — see the plan §5① / D7):
 *  - **between criteria**: [comparisonType] (All = AND, Any = OR, None = NOT(...))
 *  - **within one criterion's terms**: each criterion's own `op` (All = AND, Any = OR)
 *
 * The output is injected into a fixed `FROM` by `TransactionsDbAdapter.fetchSearch`,
 * so criteria must stay expressible as a `WHERE` fragment. Tag predicates use
 * correlated sub-queries for that reason.
 */
class SearchQueryBuilder(
    private val comparisonType: ComparisonType = ComparisonType.All,
    private val criteria: List<SearchCriteria> = emptyList()
) {
    private val active: List<SearchCriteria> get() = criteria.filterNot { it.isEmpty() }

    fun build(): String {
        val parts = active.map { "(${appendQuery(it)})" }
        if (parts.isEmpty()) return ""
        return when (comparisonType) {
            ComparisonType.All -> parts.joinToString(" AND ")
            ComparisonType.Any -> parts.joinToString(" OR ")
            // NOTE: previously fell through to OR; "None" now really negates.
            ComparisonType.None -> "NOT (" + parts.joinToString(" OR ") + ")"
        }
    }

    private fun appendQuery(criterion: SearchCriteria): String = when (criterion) {
        is SearchCriteria.Account -> appendAccount(criterion)
        is SearchCriteria.Date -> appendDate(criterion)
        is SearchCriteria.Description -> joinTerms(criterion.effectiveTerms(), criterion.op) { term ->
            columnCondition("$TRANSACTION_ALIAS${TransactionEntry.COLUMN_DESCRIPTION}", criterion.compare, term)
        }

        is SearchCriteria.Note -> joinTerms(criterion.effectiveTerms(), criterion.op) { term ->
            columnCondition("$TRANSACTION_ALIAS${TransactionEntry.COLUMN_NOTES}", criterion.compare, term)
        }

        is SearchCriteria.Number -> joinTerms(criterion.effectiveTerms(), criterion.op) { term ->
            columnCondition("$TRANSACTION_ALIAS${TransactionEntry.COLUMN_NUMBER}", criterion.compare, term)
        }

        is SearchCriteria.Memo -> joinTerms(criterion.effectiveTerms(), criterion.op) { term ->
            val first = columnCondition("$SPLIT1_ALIAS${SplitEntry.COLUMN_MEMO}", criterion.compare, term)
            val second = columnCondition("$SPLIT2_ALIAS${SplitEntry.COLUMN_MEMO}", criterion.compare, term)
            "($first) OR ($second)"
        }

        is SearchCriteria.AnyField -> appendAnyField(criterion)

        is SearchCriteria.Tag -> appendTag(criterion)
        is SearchCriteria.Numeric -> appendNumeric(criterion)
    }

    /**
     * "Any field" = every field a user can see on a transaction row: the three
     * textual transaction columns, both split memos, the **names of the two
     * split accounts** and the transaction's **tags**.
     *
     * Amount and date columns are deliberately *not* included: a fuzzy
     * `LIKE '%12%'` over `value_num`/`value_denom` or over the epoch-millis
     * `timestamp` matches noise, so those stay the job of the dedicated
     * numeric / date criteria.
     *
     * Account names and tags live in other tables, so they are expressed as
     * correlated `EXISTS` sub-queries — the fragment must stay a bare `WHERE`
     * (see the class doc).
     */
    private fun appendAnyField(criterion: SearchCriteria.AnyField): String =
        joinTerms(criterion.effectiveTerms(), criterion.op) { term ->
            anyFieldCondition(criterion.compare, term)
        }

    private fun anyFieldCondition(compare: StringCompare, term: String): String {
        val conditions = listOf(
            columnCondition("$TRANSACTION_ALIAS${TransactionEntry.COLUMN_DESCRIPTION}", compare, term),
            columnCondition("$TRANSACTION_ALIAS${TransactionEntry.COLUMN_NOTES}", compare, term),
            columnCondition("$TRANSACTION_ALIAS${TransactionEntry.COLUMN_NUMBER}", compare, term),
            columnCondition("$SPLIT1_ALIAS${SplitEntry.COLUMN_MEMO}", compare, term),
            columnCondition("$SPLIT2_ALIAS${SplitEntry.COLUMN_MEMO}", compare, term),
            accountNameCondition(SPLIT1_ALIAS, compare, term),
            accountNameCondition(SPLIT2_ALIAS, compare, term),
            anyTagCondition(compare, term)
        )
        return "(" + conditions.joinToString(" OR ") + ")"
    }

    /** Top-level [splitAlias]'s account matches [term] by name or full name. */
    private fun accountNameCondition(
        splitAlias: String,
        compare: StringCompare,
        term: String
    ): String {
        val owner = "$splitAlias${SplitEntry.COLUMN_ACCOUNT_UID}"
        val find = "${AccountEntry.TABLE_NAME} a WHERE a.${AccountEntry.COLUMN_UID} = $owner AND (" +
                columnCondition("a.${AccountEntry.COLUMN_NAME}", compare, term) + " OR " +
                columnCondition("a.${AccountEntry.COLUMN_FULL_NAME}", compare, term) + ")"
        return "EXISTS (SELECT 1 FROM $find)"
    }

    /** The transaction carries a tag matching [term]. */
    private fun anyTagCondition(compare: StringCompare, term: String): String {
        val owner = "$TRANSACTION_ALIAS${TransactionEntry.COLUMN_UID}"
        val find = "$TAG_TABLE tt WHERE tt.$TAG_COL_TRANSACTION_UID = $owner AND " +
                columnCondition("tt.$TAG_COL_TAG", compare, term)
        return "EXISTS (SELECT 1 FROM $find)"
    }

    private fun columnCondition(column: String, compare: StringCompare, term: String): String =
        when (compare) {
            StringCompare.Contains -> "$column LIKE ${sqlEscapeLike(term)}"
            StringCompare.Equals -> "$column = ${sqlEscapeString(term)}"
        }

    /**
     * One term -> [expression]; several terms -> joined by [op] with each term
     * parenthesised (so precedence can never be mis-bound).
     * A single term is emitted verbatim to stay byte-identical with the
     * historical SQL (covered by `SearchFormTest`).
     */
    private fun joinTerms(
        terms: List<String>,
        op: ComparisonType,
        expression: (String) -> String
    ): String {
        val expressions = terms.map(expression)
        return when {
            expressions.isEmpty() -> ""
            expressions.size == 1 -> expressions[0]
            op == ComparisonType.None -> "NOT (" + expressions.joinToString(" OR ") + ")"
            op == ComparisonType.Any -> expressions.joinToString(" OR ") { "($it)" }
            else -> expressions.joinToString(" AND ") { "($it)" }
        }
    }

    private fun appendAccount(criterion: SearchCriteria.Account): String {
        val uid = criterion.resolvedUid() ?: return ""
        val first = "$SPLIT1_ALIAS${SplitEntry.COLUMN_ACCOUNT_UID}"
        val second = "$SPLIT2_ALIAS${SplitEntry.COLUMN_ACCOUNT_UID}"
        val escaped = sqlEscapeString(uid)
        return when (criterion.compare) {
            ComparisonType.Any -> "($first = $escaped) OR ($second = $escaped)"
            // This is the culprit for joining `s1` and `s2`
            ComparisonType.None -> "($first <> $escaped) AND ($second <> $escaped)"
            ComparisonType.All -> ""
        }
    }

    private fun appendDate(criterion: SearchCriteria.Date): String {
        val date = criterion.value ?: return ""
        val dayStart = date.toDateTimeAtStartOfDay()
        val dayEnd = date.toDateTimeAtEndOfDay()
        val column = "$TRANSACTION_ALIAS${TransactionEntry.COLUMN_DATE_POSTED}"
        return when (criterion.compare) {
            Compare.LessThan -> "$column < ${dayEnd.toMillis()}"
            Compare.LessThanOrEqualTo -> "$column <= ${dayEnd.toMillis()}"
            Compare.EqualTo -> "$column BETWEEN ${dayStart.toMillis()} AND ${dayEnd.toMillis()}"
            Compare.NotEqualTo -> "$column NOT BETWEEN ${dayStart.toMillis()} AND ${dayEnd.toMillis()}"
            Compare.GreaterThan -> "$column > ${dayStart.toMillis()}"
            Compare.GreaterThanOrEqualTo -> "$column >= ${dayStart.toMillis()}"
        }
    }

    private fun appendNumeric(criterion: SearchCriteria.Numeric): String {
        val amount = criterion.value ?: return ""
        val first = numericForAlias(criterion, amount, SPLIT1_ALIAS)
        val second = numericForAlias(criterion, amount, SPLIT2_ALIAS)
        return "($first) OR ($second)"
    }

    private fun numericForAlias(criterion: SearchCriteria.Numeric, amount: BigDecimal, alias: String): String {
        val valueExpression = "($alias${SplitEntry.COLUMN_VALUE_NUM} * 1.0 / $alias${SplitEntry.COLUMN_VALUE_DENOM})"
        val operator = when (criterion.compare) {
            Compare.LessThan -> "<"
            Compare.LessThanOrEqualTo -> "<="
            Compare.EqualTo -> "="
            Compare.NotEqualTo -> "<>"
            Compare.GreaterThan -> ">"
            Compare.GreaterThanOrEqualTo -> ">="
        }
        val sb = StringBuilder()
        sb.append(valueExpression).append(' ').append(operator).append(' ')
            .append(amountFormatter.format(amount))
        val match = criterion.match
        if (match != null && match != NumericMatch.HasDebitsOrCredits) {
            val typeName = if (match == NumericMatch.HasDebits) {
                TransactionType.DEBIT.value
            } else {
                TransactionType.CREDIT.value
            }
            sb.insert(0, '(')
            sb.append(") AND (")
                .append(alias).append(SplitEntry.COLUMN_TYPE)
                .append(" = ").append(sqlEscapeString(typeName))
                .append(')')
        }
        return sb.toString()
    }

    /**
     * Tag predicate via correlated sub-query (so it stays a WHERE fragment).
     *  - ALL  : every requested tag present  (COUNT(DISTINCT tag) = n)
     *  - ANY  : at least one present
     *  - NONE : none present
     *
     * ⚠️ Table/columns must match feature ⑧'s `transaction_tags` schema.
     */
    private fun appendTag(criterion: SearchCriteria.Tag): String {
        val tags = criterion.tags.filter { it.isNotBlank() }
        if (tags.isEmpty()) return ""
        val inList = tags.joinToString(", ") { sqlEscapeString(it) }
        val owner = "$TRANSACTION_ALIAS${TransactionEntry.COLUMN_UID}"
        val find = "$TAG_TABLE tt WHERE tt.$TAG_COL_TRANSACTION_UID = $owner AND tt.$TAG_COL_TAG IN ($inList)"
        val exists = "EXISTS (SELECT 1 FROM $find)"
        return when (criterion.op) {
            ComparisonType.Any -> exists
            ComparisonType.None -> "NOT $exists"
            ComparisonType.All ->
                "(SELECT COUNT(DISTINCT tt.$TAG_COL_TAG) FROM $find) = ${tags.size}"
        }
    }

    companion object {
        private const val TRANSACTION_ALIAS = "t."
        private const val SPLIT1_ALIAS = "s1."
        private const val SPLIT2_ALIAS = "s2."

        // Must stay in sync with feature ⑧'s schema.
        const val TAG_TABLE = "transaction_tags"
        const val TAG_COL_TRANSACTION_UID = "transaction_uid"
        const val TAG_COL_TAG = "tag"

        /**
         * Bare WHERE fragment matching the transactions that carry [tag].
         *
         * Reuses [appendTag] through the regular constructor so a chip-driven
         * filter and a form-driven criterion can never drift apart.
         * Returns an empty string for a blank tag (callers must treat that as
         * "no filter" rather than "match everything").
         */
        fun tagWhere(tag: String, op: ComparisonType = ComparisonType.Any): String {
            val trimmed = tag.trim()
            if (trimmed.isEmpty()) return ""
            val criterion = SearchCriteria.Tag(mutableListOf(trimmed), op)
            return SearchQueryBuilder(op, listOf(criterion)).build()
        }

        /**
         * Combines a base WHERE fragment with an extra predicate using AND.
         * Both sides are parenthesised so an `OR` inside either one can never
         * re-bind (the search layer builds bare fragments, and precedence is
         * the caller's problem).
         */
        fun andWhere(base: String?, extra: String): String {
            val left = base?.trim().orEmpty()
            val right = extra.trim()
            if (right.isEmpty()) return left
            if (left.isEmpty()) return right
            return "($left) AND ($right)"
        }

        private val amountFormatter by lazy {
            (NumberFormat.getInstance(Locale.ROOT) as DecimalFormat).apply {
                isGroupingUsed = false
                maximumFractionDigits = 6
            }
        }
    }
}
