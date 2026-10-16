package org.gnucash.android.ui.search

/**
 * Plain-text ("全文") query support for transaction search.
 *
 * A query is a small boolean expression over fuzzy terms. The compiled result is
 * a bare SQL `WHERE` fragment, exactly like [SearchQueryBuilder], so it can be
 * injected through [SearchResultsFragment.EXTRA_FORM] into
 * `TransactionsDbAdapter.fetchSearch`.
 *
 * ## Syntax
 *
 * | you type | it means |
 * |---|---|
 * | `打车 现金` | `打车` **AND** `现金` (space = and) |
 * | `打车 | 地铁` | `打车` **OR** `地铁` |
 * | `打车 not 地铁` | `打车` **AND NOT** `地铁` |
 * | `"共享 单车"` | the phrase, kept as a single term |
 * | `(a | b) c` | parentheses group, then and-ed with `c` |
 *
 * Precedence (highest first): `not`, juxtaposition (and), `|` (or).
 * `,` `，` `、` also separate terms (and). A term matches **every field a user
 * can see on a transaction row** — the description, notes, number, both split
 * memos, both split account names and the tags — with a fuzzy `LIKE '%term%'`
 * (wildcards inside the term are escaped). The amount and date columns stay out
 * of the fuzzy match on purpose: `LIKE '%12%'` over `value_num`/`value_denom`
 * or the epoch-millis `timestamp` matches noise; those have dedicated criteria.
 *
 * `not` (any case) is the negation operator; quote it (`"not"`) to search for
 * the literal word. Unbalanced parentheses and a dangling `not` are tolerated —
 * they are dropped instead of failing the whole query.
 *
 * ## Negation and SQL NULLs
 *
 * A term's predicate evaluates to SQL `NULL` (not `0`) whenever none of the
 * fields match **and** at least one of them is `NULL` — `0 OR NULL` is `NULL` in
 * SQLite. `NOT NULL` is `NULL`, which silently drops those rows, so a negation
 * is compiled as `NOT COALESCE(<predicate>, 0)`. "Does not contain X" then really
 * means *does not contain X*, even for a transaction whose memos are empty.
 */
object FullTextQuery {

    /** Parsed query tree. */
    sealed interface Expr

    /** A single fuzzy term. */
    data class Term(val text: String) : Expr

    /** `not <inner>`. */
    data class Negate(val inner: Expr) : Expr

    /** `<items[0]> AND <items[1]> ...` (always 2+ items). */
    data class Conjunction(val items: List<Expr>) : Expr

    /** `<items[0]> OR <items[1]> ...` (always 2+ items). */
    data class Disjunction(val items: List<Expr>) : Expr

    private const val OR_ASCII = '|'
    private const val OR_FULL = '\uFF5C'
    private const val OPEN_ASCII = '('
    private const val CLOSE_ASCII = ')'
    private const val OPEN_FULL = '\uFF08'
    private const val CLOSE_FULL = '\uFF09'
    private const val QUOTE = '"'
    private const val NOT_KEYWORD = "not"

    /**
     * Parses [raw] into an [Expr], or returns `null` when there is nothing
     * searchable in it (blank input, or only operators).
     */
    fun parse(raw: String?): Expr? {
        if (raw.isNullOrBlank()) return null
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return null
        return Parser(tokens).parseOr()
    }

    /** Parses then compiles [raw]; returns `""` when there is nothing to search. */
    fun build(raw: String?): String {
        val expr = parse(raw) ?: return ""
        return compile(expr)
    }

    /** Compiles [expr] into a bare SQL `WHERE` fragment. */
    fun compile(expr: Expr): String = when (expr) {
        is Term -> leaf(expr.text)
        // COALESCE turns the "no field matched but a field is NULL" case into a
        // plain false, so the negation means what a user expects. See the class
        // doc.
        is Negate -> "NOT COALESCE(${compile(expr.inner)}, 0)"
        is Conjunction -> join(expr.items, " AND ")
        is Disjunction -> join(expr.items, " OR ")
    }

    private fun join(items: List<Expr>, separator: String): String {
        val parts = items.map { compile(it) }
        if (parts.size == 1) return parts[0]
        return "(" + parts.joinToString(separator) + ")"
    }

    /**
     * One fuzzy term -> predicate over every textual field of a transaction.
     *
     * Delegates to [SearchQueryBuilder] so the "which columns, and how" knowledge
     * keeps exactly one implementation (the same one the form-based search and
     * the tag chip filter use).
     */
    private fun leaf(term: String): String = SearchQueryBuilder(
        ComparisonType.All,
        listOf(
            SearchCriteria.AnyField(
                terms = mutableListOf(term),
                op = ComparisonType.All,
                compare = StringCompare.Contains
            )
        )
    ).build()

    //region tokenizer

    private sealed interface Token {
        class Word(val text: String) : Token
        object Or : Token
        object Negate : Token
        object Open : Token
        object Close : Token
    }

    private fun tokenize(raw: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val word = StringBuilder()
        var inQuotes = false
        var quoted = false

        fun flush() {
            val text = word.toString().trim()
            val wasQuoted = quoted
            word.setLength(0)
            quoted = false
            if (text.isEmpty()) return
            if (!wasQuoted && text.equals(NOT_KEYWORD, ignoreCase = true)) {
                tokens.add(Token.Negate)
            } else {
                tokens.add(Token.Word(text))
            }
        }

        for (ch in raw) {
            when {
                ch == QUOTE -> {
                    if (inQuotes) {
                        // Closing quote: the accumulated word is a literal phrase.
                        quoted = true
                        inQuotes = false
                    } else {
                        // Opening quote: end whatever bare word preceded it, so
                        // `not"地铁"` still reads as the operator `not`.
                        flush()
                        inQuotes = true
                    }
                }

                inQuotes -> word.append(ch)

                ch == OR_ASCII || ch == OR_FULL -> {
                    flush()
                    tokens.add(Token.Or)
                }

                ch == OPEN_ASCII || ch == OPEN_FULL -> {
                    flush()
                    tokens.add(Token.Open)
                }

                ch == CLOSE_ASCII || ch == CLOSE_FULL -> {
                    flush()
                    tokens.add(Token.Close)
                }

                ch.isWhitespace() || ch == ',' || ch == '\uFF0C' || ch == '\u3001' -> flush()

                else -> word.append(ch)
            }
        }
        flush()
        return tokens
    }

    //endregion

    //region parser

    /**
     * Recursive descent over the token list.
     *
     * ```
     * Or    := And ( '|' And )*
     * And   := Unary ( Unary )*        // juxtaposition
     * Unary := 'not' Unary | Primary
     * Primary := '(' Or ')' | Word
     * ```
     *
     * Always makes progress (every branch either consumes a token or returns), so
     * malformed input degrades instead of looping or throwing.
     */
    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        private fun peek(): Token? = tokens.getOrNull(index)

        private fun advance(): Token? = tokens.getOrNull(index++)

        fun parseOr(): Expr? {
            val first = parseAnd() ?: return null
            val items = mutableListOf(first)
            while (peek() is Token.Or) {
                advance()
                parseAnd()?.let { items.add(it) }
            }
            return if (items.size == 1) first else Disjunction(items)
        }

        private fun parseAnd(): Expr? {
            val first = parseUnary() ?: return null
            val items = mutableListOf(first)
            while (true) {
                val token = peek() ?: break
                if (token !is Token.Word && token !is Token.Negate && token !is Token.Open) break
                val operand = parseUnary() ?: break
                items.add(operand)
            }
            return if (items.size == 1) first else Conjunction(items)
        }

        private fun parseUnary(): Expr? {
            when (val token = peek()) {
                is Token.Negate -> {
                    advance()
                    // A dangling `not` is dropped rather than failing the query.
                    val inner = parseUnary() ?: return null
                    return Negate(inner)
                }

                is Token.Open -> {
                    advance()
                    val inner = parseOr()
                    // Tolerate a missing ')': the group simply ends with the input.
                    if (peek() is Token.Close) advance()
                    return inner
                }

                is Token.Word -> {
                    advance()
                    return Term(token.text)
                }

                // A stray '|' or ')' where an operand was expected: skip it so the
                // caller's loop can make progress.
                Token.Or, Token.Close -> {
                    advance()
                    return null
                }

                null -> return null
            }
        }
    }

    //endregion
}
