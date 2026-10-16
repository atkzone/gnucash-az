package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseSchema.AccountEntry
import org.gnucash.android.db.DatabaseSchema.SplitEntry
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test

/**
 * Unit tests for the plain-text ("全文") query parser + compiler.
 *
 * The boolean composition is asserted with exact strings: a stray extra
 * parenthesis or a missing `COALESCE` must fail here, not in the field.
 */
class FullTextQueryTest : GnuCashTest() {

    private val descriptionColumn = "t." + TransactionEntry.COLUMN_DESCRIPTION
    private val notesColumn = "t." + TransactionEntry.COLUMN_NOTES
    private val numberColumn = "t." + TransactionEntry.COLUMN_NUMBER
    private val uidColumn = "t." + TransactionEntry.COLUMN_UID
    private val memo1Column = "s1." + SplitEntry.COLUMN_MEMO
    private val memo2Column = "s2." + SplitEntry.COLUMN_MEMO
    private val account1Column = "s1." + SplitEntry.COLUMN_ACCOUNT_UID
    private val account2Column = "s2." + SplitEntry.COLUMN_ACCOUNT_UID

    /** A split's account matches [term] by name or full name. */
    private fun accountPredicate(splitAccountColumn: String, term: String) =
        "EXISTS (SELECT 1 FROM ${AccountEntry.TABLE_NAME} a WHERE " +
                "a.${AccountEntry.COLUMN_UID} = $splitAccountColumn AND " +
                "(a.${AccountEntry.COLUMN_NAME} LIKE '%$term%' OR " +
                "a.${AccountEntry.COLUMN_FULL_NAME} LIKE '%$term%'))"

    /** The transaction carries a tag matching [term]. */
    private fun tagPredicate(term: String) =
        "EXISTS (SELECT 1 FROM ${SearchQueryBuilder.TAG_TABLE} tt WHERE " +
                "tt.${SearchQueryBuilder.TAG_COL_TRANSACTION_UID} = $uidColumn AND " +
                "tt.${SearchQueryBuilder.TAG_COL_TAG} LIKE '%$term%')"

    /**
     * The predicate a single fuzzy term compiles to — the "any field" shape
     * already pinned by `SearchQueryBuilderTest`: the three textual transaction
     * columns, both split memos, both split account names and the tags.
     */
    private fun leaf(term: String) = "((" +
            "$descriptionColumn LIKE '%$term%' OR " +
            "$notesColumn LIKE '%$term%' OR " +
            "$numberColumn LIKE '%$term%' OR " +
            "$memo1Column LIKE '%$term%' OR " +
            "$memo2Column LIKE '%$term%' OR " +
            accountPredicate(account1Column, term) + " OR " +
            accountPredicate(account2Column, term) + " OR " +
            tagPredicate(term) +
            "))"

    // ---- a single term -------------------------------------------------------

    @Test
    fun `a single term searches every text column`() {
        assertThat(FullTextQuery.build("打车")).isEqualTo(leaf("打车"))
    }

    @Test
    fun `a term is fuzzy - not anchored`() {
        assertThat(FullTextQuery.build("车")).contains("LIKE '%车%'")
    }

    @Test
    fun `a term also reaches the split account names and the tags`() {
        val sql = FullTextQuery.build("报销")
        assertThat(sql).contains("FROM accounts a")
        assertThat(sql).contains("a.full_name LIKE '%报销%'")
        assertThat(sql).contains("FROM transaction_tags tt")
        assertThat(sql).contains("tt.tag LIKE '%报销%'")
    }

    @Test
    fun `quotes inside a term are escaped`() {
        // A search box must never be able to break out of the SQL string literal.
        assertThat(FullTextQuery.build("o'brien")).contains("'%o''brien%'")
    }

    // ---- nothing to search ---------------------------------------------------

    @Test
    fun `blank input searches nothing`() {
        assertThat(FullTextQuery.build(null)).isEmpty()
        assertThat(FullTextQuery.build("")).isEmpty()
        assertThat(FullTextQuery.build("   ")).isEmpty()
        assertThat(FullTextQuery.build("  ,、， ")).isEmpty()
    }

    @Test
    fun `operators alone search nothing`() {
        assertThat(FullTextQuery.build("|")).isEmpty()
        assertThat(FullTextQuery.build("( )")).isEmpty()
        assertThat(FullTextQuery.build("not")).isEmpty()
    }

    @Test
    fun `parse returns null when there is no term`() {
        assertThat(FullTextQuery.parse("  ")).isNull()
        assertThat(FullTextQuery.parse("| |")).isNull()
        assertThat(FullTextQuery.parse(null)).isNull()
    }

    // ---- boolean operators ---------------------------------------------------

    @Test
    fun `a space means and`() {
        assertThat(FullTextQuery.build("打车 现金"))
            .isEqualTo("(${leaf("打车")} AND ${leaf("现金")})")
    }

    @Test
    fun `a pipe means or`() {
        assertThat(FullTextQuery.build("打车 | 地铁"))
            .isEqualTo("(${leaf("打车")} OR ${leaf("地铁")})")
    }

    @Test
    fun `a leading not negates the term`() {
        assertThat(FullTextQuery.build("not 地铁"))
            .isEqualTo("NOT COALESCE(${leaf("地铁")}, 0)")
    }

    @Test
    fun `not between terms keeps the conjunction`() {
        assertThat(FullTextQuery.build("打车 not 地铁"))
            .isEqualTo("(${leaf("打车")} AND NOT COALESCE(${leaf("地铁")}, 0))")
    }

    @Test
    fun `not is case insensitive`() {
        assertThat(FullTextQuery.build("打车 NOT 地铁"))
            .isEqualTo("(${leaf("打车")} AND NOT COALESCE(${leaf("地铁")}, 0))")
    }

    // ---- precedence ----------------------------------------------------------

    @Test
    fun `not binds tighter than and`() {
        assertThat(FullTextQuery.build("not 打车 地铁"))
            .isEqualTo("(NOT COALESCE(${leaf("打车")}, 0) AND ${leaf("地铁")})")
    }

    @Test
    fun `and binds tighter than or`() {
        assertThat(FullTextQuery.build("打车 | 现金 地铁"))
            .isEqualTo("(${leaf("打车")} OR (${leaf("现金")} AND ${leaf("地铁")}))")
    }

    @Test
    fun `parentheses regroup`() {
        assertThat(FullTextQuery.build("(打车 | 现金) 地铁"))
            .isEqualTo("((${leaf("打车")} OR ${leaf("现金")}) AND ${leaf("地铁")})")
    }

    // ---- separators ----------------------------------------------------------

    @Test
    fun `full width pipe and parentheses work too`() {
        assertThat(FullTextQuery.build("打车｜现金"))
            .isEqualTo("(${leaf("打车")} OR ${leaf("现金")})")
        assertThat(FullTextQuery.build("（打车 现金）"))
            .isEqualTo("(${leaf("打车")} AND ${leaf("现金")})")
    }

    @Test
    fun `comma and ideographic comma separate terms`() {
        assertThat(FullTextQuery.build("打车，现金、地铁"))
            .isEqualTo("(${leaf("打车")} AND ${leaf("现金")} AND ${leaf("地铁")})")
    }

    // ---- quoting -------------------------------------------------------------

    @Test
    fun `quotes keep a phrase as one term`() {
        assertThat(FullTextQuery.build("\"共享 单车\""))
            .isEqualTo(leaf("共享 单车"))
    }

    @Test
    fun `a quoted not is a literal term`() {
        assertThat(FullTextQuery.build("\"not\"")).isEqualTo(leaf("not"))
    }

    @Test
    fun `quotes keep a pipe literal`() {
        assertThat(FullTextQuery.build("\"a|b\"")).isEqualTo(leaf("a|b"))
    }

    // ---- malformed input is tolerated ---------------------------------------

    @Test
    fun `a dangling not is dropped`() {
        assertThat(FullTextQuery.build("打车 not")).isEqualTo(leaf("打车"))
    }

    @Test
    fun `an unclosed parenthesis is tolerated`() {
        assertThat(FullTextQuery.build("(打车 现金"))
            .isEqualTo("(${leaf("打车")} AND ${leaf("现金")})")
    }

    // ---- parser shape --------------------------------------------------------

    @Test
    fun `parse builds the expected tree`() {
        assertThat(FullTextQuery.parse("打车")).isEqualTo(FullTextQuery.Term("打车"))
        assertThat(FullTextQuery.parse("not 打车"))
            .isEqualTo(FullTextQuery.Negate(FullTextQuery.Term("打车")))
        assertThat(FullTextQuery.parse("a b")).isEqualTo(
            FullTextQuery.Conjunction(
                listOf(FullTextQuery.Term("a"), FullTextQuery.Term("b"))
            )
        )
        assertThat(FullTextQuery.parse("a | b")).isEqualTo(
            FullTextQuery.Disjunction(
                listOf(FullTextQuery.Term("a"), FullTextQuery.Term("b"))
            )
        )
    }

    // ---- contract ------------------------------------------------------------

    @Test
    fun `a negation is null safe`() {
        // `0 OR NULL` is NULL in SQLite and `NOT NULL` is NULL, so without
        // COALESCE every transaction with an empty memo would silently drop out
        // of "does not contain". The guard must stay.
        val sql = FullTextQuery.build("not 地铁")
        assertThat(sql).startsWith("NOT COALESCE(")
        assertThat(sql).doesNotStartWith("NOT (")
    }

    @Test
    fun `the output is a bare where fragment`() {
        val sql = FullTextQuery.build("打车 not 地铁")
        // The fragment owns no statement of its own: the caller injects it into a
        // fixed FROM (`TransactionsDbAdapter.fetchSearch`).
        assertThat(sql.trim()).doesNotStartWith("SELECT")
        assertThat(sql).doesNotContain(" FROM ${TransactionEntry.TABLE_NAME}")
        // It leans on the caller's table aliases instead of naming tables itself.
        assertThat(sql).contains("$descriptionColumn LIKE")
        assertThat(sql).contains("s1.")
        // Correlated sub-queries (accounts / tags) are allowed, but the parentheses
        // must stay balanced so an inner OR can never re-bind against the caller's
        // AND — note the tag criterion has always worked this way.
        assertThat(sql.count { it == '(' }).isEqualTo(sql.count { it == ')' })
    }
}
