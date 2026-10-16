package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseSchema.SplitEntry
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test

/**
 * Unit tests for the extracted [SearchQueryBuilder] + [SearchTerms].
 *
 * Covers the ①a increment: multi-keyword AND/OR, the "any field" search, the
 * (previously unimplemented) `None` negation, and the tag predicate.
 */
class SearchQueryBuilderTest : GnuCashTest() {

    private val descriptionColumn = "t." + TransactionEntry.COLUMN_DESCRIPTION
    private val notesColumn = "t." + TransactionEntry.COLUMN_NOTES
    private val numberColumn = "t." + TransactionEntry.COLUMN_NUMBER
    private val memo1Column = "s1." + SplitEntry.COLUMN_MEMO
    private val memo2Column = "s2." + SplitEntry.COLUMN_MEMO
    private val uidColumn = "t." + TransactionEntry.COLUMN_UID

    // ---- multi-keyword -------------------------------------------------------

    @Test
    fun `single value keeps legacy SQL`() {
        val form = SearchForm()
        form.addDescription().apply {
            value = "zebra"
            compare = StringCompare.Contains
        }
        assertThat(form.buildSQL()).isEqualTo("($descriptionColumn LIKE '%zebra%')")
    }

    @Test
    fun `multiple terms with All are ANDed`() {
        val form = SearchForm()
        form.addDescription().apply {
            terms = mutableListOf("alpha", "beta")
            op = ComparisonType.All
            compare = StringCompare.Contains
        }
        val expected = "(($descriptionColumn LIKE '%alpha%') AND ($descriptionColumn LIKE '%beta%'))"
        assertThat(form.buildSQL()).isEqualTo(expected)
    }

    @Test
    fun `multiple terms with Any are ORed`() {
        val form = SearchForm()
        form.addDescription().apply {
            terms = mutableListOf("alpha", "beta")
            op = ComparisonType.Any
            compare = StringCompare.Contains
        }
        val expected = "(($descriptionColumn LIKE '%alpha%') OR ($descriptionColumn LIKE '%beta%'))"
        assertThat(form.buildSQL()).isEqualTo(expected)
    }

    // ---- criterion-level boolean --------------------------------------------

    @Test
    fun `Any comparison ORs criteria`() {
        val form = SearchForm(ComparisonType.Any)
        form.addDescription().apply { value = "alpha" }
        form.addNote().apply { value = "beta" }
        // Each criterion is already parenthesised; the join adds no extra layer.
        val expected = "($descriptionColumn LIKE '%alpha%') OR ($notesColumn LIKE '%beta%')"
        assertThat(form.buildSQL()).isEqualTo(expected)
    }

    @Test
    fun `None comparison negates the whole expression`() {
        val form = SearchForm(ComparisonType.None)
        form.addDescription().apply { value = "alpha" }
        form.addNote().apply { value = "beta" }
        val expected = "NOT (($descriptionColumn LIKE '%alpha%') OR ($notesColumn LIKE '%beta%'))"
        assertThat(form.buildSQL()).isEqualTo(expected)
    }

    // ---- any field -----------------------------------------------------------

    /** A split's account matches [term] by name or full name. */
    private fun accountPredicate(alias: String, term: String) =
        "EXISTS (SELECT 1 FROM accounts a WHERE a.uid = $alias.account_uid AND " +
                "(a.name LIKE '%$term%' OR a.full_name LIKE '%$term%'))"

    /** The transaction carries a tag matching [term]. */
    private fun tagPredicate(term: String) =
        "EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_uid = $uidColumn AND " +
                "tt.tag LIKE '%$term%')"

    private fun anyFieldPredicate(term: String) = "((" +
            "$descriptionColumn LIKE '%$term%' OR " +
            "$notesColumn LIKE '%$term%' OR " +
            "$numberColumn LIKE '%$term%' OR " +
            "$memo1Column LIKE '%$term%' OR " +
            "$memo2Column LIKE '%$term%' OR " +
            accountPredicate("s1", term) + " OR " +
            accountPredicate("s2", term) + " OR " +
            tagPredicate(term) +
            "))"

    @Test
    fun `any field searches every visible field`() {
        val form = SearchForm()
        form.addAnyField().apply { value = "alpha" }
        assertThat(form.buildSQL()).isEqualTo(anyFieldPredicate("alpha"))
    }

    @Test
    fun `any field also matches the split account names and the tags`() {
        val sql = SearchForm()
            .apply { addAnyField().apply { value = "alpha" } }
            .buildSQL()
        // Account names live in `accounts`, tags in `transaction_tags`; both must
        // be reached through a correlated sub-query so the fragment stays WHERE-only.
        assertThat(sql).contains("FROM accounts a")
        assertThat(sql).contains("a.full_name LIKE '%alpha%'")
        assertThat(sql).contains("FROM transaction_tags tt")
        assertThat(sql).contains("tt.tag LIKE '%alpha%'")
    }

    @Test
    fun `any field keeps the amount and date columns out of the fuzzy match`() {
        val sql = SearchForm()
            .apply { addAnyField().apply { value = "12" } }
            .buildSQL()
        // A fuzzy LIKE over value_num/value_denom/timestamp is noise, so those
        // columns stay with the dedicated numeric/date criteria.
        assertThat(sql).doesNotContain("value_num")
        assertThat(sql).doesNotContain("value_denom")
        assertThat(sql).doesNotContain("timestamp")
    }

    // ---- tags ----------------------------------------------------------------

    @Test
    fun `tag Any uses EXISTS`() {
        val form = SearchForm()
        form.addTag().apply {
            tags = mutableListOf("food")
            op = ComparisonType.Any
        }
        val expected = "(EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_uid = " +
                "$uidColumn AND tt.tag IN ('food')))"
        assertThat(form.buildSQL()).isEqualTo(expected)
    }

    @Test
    fun `tag All requires every tag`() {
        val form = SearchForm()
        form.addTag().apply {
            tags = mutableListOf("food", "drink")
            op = ComparisonType.All
        }
        val expected = "((SELECT COUNT(DISTINCT tt.tag) FROM transaction_tags tt WHERE " +
                "tt.transaction_uid = $uidColumn AND tt.tag IN ('food', 'drink')) = 2)"
        assertThat(form.buildSQL()).isEqualTo(expected)
    }

    @Test
    fun `tag None excludes the tag`() {
        val form = SearchForm()
        form.addTag().apply {
            tags = mutableListOf("food")
            op = ComparisonType.None
        }
        val expected = "(NOT EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_uid = " +
                "$uidColumn AND tt.tag IN ('food')))"
        assertThat(form.buildSQL()).isEqualTo(expected)
    }

    @Test
    fun `empty tag criterion is ignored`() {
        val form = SearchForm()
        form.addTag()
        assertThat(form.buildSQL()).isEmpty()
    }

    // ---- tokenizer -----------------------------------------------------------

    @Test
    fun `tokenizer splits on whitespace and defaults to AND`() {
        val (terms, op) = SearchTerms.parse("alpha beta")
        assertThat(terms).containsExactly("alpha", "beta")
        assertThat(op).isEqualTo(ComparisonType.All)
    }

    @Test
    fun `tokenizer switches to OR on pipe`() {
        val (terms, op) = SearchTerms.parse("alpha | beta")
        assertThat(terms).containsExactly("alpha", "beta")
        assertThat(op).isEqualTo(ComparisonType.Any)
    }

    @Test
    fun `tokenizer keeps quoted phrase as a single term`() {
        val (terms, op) = SearchTerms.parse("\"共享单车\"")
        assertThat(terms).containsExactly("共享单车")
        assertThat(op).isEqualTo(ComparisonType.All)
    }

    @Test
    fun `tokenizer handles full width space`() {
        val (terms, _) = SearchTerms.parse("共享单车\u3000地铁")
        assertThat(terms).containsExactly("共享单车", "地铁")
    }

    @Test
    fun `tokenizer splits on comma and ideographic comma`() {
        val (terms, _) = SearchTerms.parse("a, b、c")
        assertThat(terms).containsExactly("a", "b", "c")
    }
}
