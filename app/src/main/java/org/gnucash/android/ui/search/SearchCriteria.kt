package org.gnucash.android.ui.search

import org.gnucash.android.util.toMillis
import org.joda.time.LocalDate
import java.math.BigDecimal

enum class ComparisonType {
    All,
    Any,
    None
}

enum class StringCompare {
    Contains,
    Equals
    //TODO Regex
}

enum class Compare {
    // QOF_COMPARE_LT
    LessThan,

    // QOF_COMPARE_LTE
    LessThanOrEqualTo,

    // QOF_COMPARE_EQUAL
    EqualTo,

    // QOF_COMPARE_NEQ
    NotEqualTo,

    // QOF_COMPARE_GT
    GreaterThan,

    // QOF_COMPARE_GTE
    GreaterThanOrEqualTo
}

enum class NumericMatch {
    // QOF_NUMERIC_MATCH_ANY
    HasDebitsOrCredits,

    // QOF_NUMERIC_MATCH_CREDIT
    HasCredits,

    // QOF_NUMERIC_MATCH_DEBIT
    HasDebits
}

/**
 * Effective terms for a criterion: explicit [terms] win; otherwise fall back to
 * the legacy single `value`, so existing call sites keep working unchanged.
 */
internal fun resolveTerms(value: String?, terms: List<String>): List<String> {
    val cleaned = terms.filter { it.isNotBlank() }
    return when {
        cleaned.isNotEmpty() -> cleaned
        value.isNullOrBlank() -> emptyList()
        else -> listOf(value)
    }
}

sealed class SearchCriteria {
    abstract fun isEmpty(): Boolean

    /** Terms to match for this criterion (empty for non-textual criteria). */
    open fun effectiveTerms(): List<String> = emptyList()

    data class Description(
        var value: String? = null,
        var compare: StringCompare = StringCompare.Contains,
        var terms: MutableList<String> = mutableListOf(),
        var op: ComparisonType = ComparisonType.All
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = value == null && terms.none { it.isNotBlank() }
        override fun effectiveTerms(): List<String> = resolveTerms(value, terms)
    }

    data class Note(
        var value: String? = null,
        var compare: StringCompare = StringCompare.Contains,
        var terms: MutableList<String> = mutableListOf(),
        var op: ComparisonType = ComparisonType.All
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = value == null && terms.none { it.isNotBlank() }
        override fun effectiveTerms(): List<String> = resolveTerms(value, terms)
    }

    data class Memo(
        var value: String? = null,
        var compare: StringCompare = StringCompare.Contains,
        var terms: MutableList<String> = mutableListOf(),
        var op: ComparisonType = ComparisonType.All
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = value == null && terms.none { it.isNotBlank() }
        override fun effectiveTerms(): List<String> = resolveTerms(value, terms)
    }

    data class Number(
        var value: String? = null,
        var compare: StringCompare = StringCompare.Contains,
        var terms: MutableList<String> = mutableListOf(),
        var op: ComparisonType = ComparisonType.All
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = value == null && terms.none { it.isNotBlank() }
        override fun effectiveTerms(): List<String> = resolveTerms(value, terms)
    }

    /**
     * Free-text search across the most common textual fields at once
     * (description / notes / number / both split memos) — i.e. "全字段搜索".
     */
    data class AnyField(
        var value: String? = null,
        var compare: StringCompare = StringCompare.Contains,
        var terms: MutableList<String> = mutableListOf(),
        var op: ComparisonType = ComparisonType.All
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = value == null && terms.none { it.isNotBlank() }
        override fun effectiveTerms(): List<String> = resolveTerms(value, terms)
    }

    /**
     * Tag predicate. Expressed as a correlated sub-query so it can live in a
     * bare WHERE fragment (no FROM/JOIN change needed).
     *
     * ⚠️ Depends on feature ⑧ (`transaction_tags` table). Until that lands the
     * predicate compiles and is testable, but there is no data to match.
     */
    data class Tag(
        var tags: MutableList<String> = mutableListOf(),
        var op: ComparisonType = ComparisonType.All
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = tags.none { it.isNotBlank() }
    }

    data class Date(
        var value: LocalDate? = null,
        var compare: Compare = Compare.LessThanOrEqualTo
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = (value == null) || value.toMillis() <= 0L

        fun set(year: Int, monthOfYear: Int, dayOfMonth: Int): LocalDate {
            val date = LocalDate(year, monthOfYear, dayOfMonth)
            value = date
            return date
        }
    }

    data class Numeric(
        var value: BigDecimal? = null,
        var match: NumericMatch? = null,
        var compare: Compare = Compare.EqualTo
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean = value == null
    }

    data class Account(
        var value: org.gnucash.android.model.Account? = null,
        var compare: ComparisonType = ComparisonType.Any,
        /**
         * Fallback UID, kept so a deserialised template never silently loses the
         * account filter when the [value] object cannot be resolved.
         */
        var uid: String? = null
    ) : SearchCriteria() {
        override fun isEmpty(): Boolean =
            value?.uid.isNullOrEmpty() && uid.isNullOrEmpty()

        /** The UID to filter on: the resolved account's, else the stored fallback. */
        fun resolvedUid(): String? = value?.uid?.takeIf { it.isNotEmpty() } ?: uid
    }
}

/**
 * Structural copy whose mutable collections are independent of the source.
 *
 * Used when applying a saved template: the applied criteria must be editable
 * without mutating the in-memory template they came from. (A plain data-class
 * `copy()` would share the `MutableList` instances.)
 */
internal fun SearchCriteria.copyForApply(): SearchCriteria = when (this) {
    is SearchCriteria.Description -> copy(terms = terms.toMutableList())
    is SearchCriteria.Note -> copy(terms = terms.toMutableList())
    is SearchCriteria.Memo -> copy(terms = terms.toMutableList())
    is SearchCriteria.Number -> copy(terms = terms.toMutableList())
    is SearchCriteria.AnyField -> copy(terms = terms.toMutableList())
    is SearchCriteria.Tag -> copy(tags = tags.toMutableList())
    is SearchCriteria.Date -> copy()
    is SearchCriteria.Numeric -> copy()
    is SearchCriteria.Account -> copy()
}

/**
 * Parses a raw user query string into terms plus the term-level boolean op.
 *
 * Rules:
 *  - whitespace / `,` / `，` / `、` separate terms
 *  - a bare `|` (or `｜`) switches the op to [ComparisonType.Any] (OR)
 *  - double quotes group a phrase, so `"共享单车"` stays one term
 *  - default op is [ComparisonType.All] (AND)
 */
object SearchTerms {
    private const val OR_ASCII = '|'
    private const val OR_FULL = '\uFF5C'

    fun parse(raw: String): Pair<List<String>, ComparisonType> {
        val terms = mutableListOf<String>()
        val sb = StringBuilder()
        var op = ComparisonType.All
        var inQuotes = false

        fun flush() {
            val term = sb.toString().trim()
            if (term.isNotEmpty()) terms.add(term)
            sb.setLength(0)
        }

        for (ch in raw) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && (ch == OR_ASCII || ch == OR_FULL) -> {
                    flush()
                    op = ComparisonType.Any
                }

                !inQuotes && (ch.isWhitespace() || ch == ',' || ch == '\uFF0C' || ch == '\u3001') -> flush()
                else -> sb.append(ch)
            }
        }
        flush()
        return terms to op
    }
}
