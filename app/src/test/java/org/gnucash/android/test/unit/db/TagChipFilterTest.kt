package org.gnucash.android.test.unit.db

import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.databinding.CardviewTransactionBinding
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TagsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Account
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.search.ComparisonType
import org.gnucash.android.ui.search.SearchCriteria
import org.gnucash.android.ui.search.SearchForm
import org.gnucash.android.ui.search.SearchQueryBuilder
import org.gnucash.android.ui.search.SearchResultViewHolder
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Feature ⑧ display layer (plan §5①b / decision D2): tapping a tag chip on a
 * search result must narrow the current results **without** losing the query the
 * screen was opened with.
 *
 * The chip builds its predicate through [SearchQueryBuilder.tagWhere] and ANDs it
 * onto the original WHERE via [SearchQueryBuilder.andWhere] — both are asserted
 * here as pure strings, then as real rows in the database.
 */
class TagChipFilterTest : GnuCashTest() {
    private lateinit var tagsDbAdapter: TagsDbAdapter
    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var transactionsDbAdapter: TransactionsDbAdapter
    private lateinit var alphaAccount: Account
    private lateinit var bravoAccount: Account

    private lateinit var lunch: Transaction
    private lateinit var taxi: Transaction

    @Before
    fun setUp() {
        tagsDbAdapter = TagsDbAdapter.instance
        accountsDbAdapter = AccountsDbAdapter.instance
        transactionsDbAdapter = TransactionsDbAdapter.instance

        alphaAccount = Account("Chip alpha")
        bravoAccount = Account("Chip bravo")
        accountsDbAdapter.addRecord(bravoAccount)
        accountsDbAdapter.addRecord(alphaAccount)

        lunch = save("午饭", "午饭 #餐饮 #报销")
        taxi = save("打车", "打车 #出行")
    }

    @After
    fun tearDown() {
        transactionsDbAdapter.deleteAllRecords()
        tagsDbAdapter.deleteAllRecords()
        accountsDbAdapter.deleteAllRecords()
    }

    // ---- SQL layer ---------------------------------------------------------

    @Test
    fun `tagWhere builds an EXISTS predicate against transaction_tags`() {
        val sql = SearchQueryBuilder.tagWhere("餐饮")

        assertThat(sql).contains(SearchQueryBuilder.TAG_TABLE)
        assertThat(sql).contains(SearchQueryBuilder.TAG_COL_TRANSACTION_UID)
        assertThat(sql).contains("'餐饮'")
        assertThat(sql).startsWith("(EXISTS")
    }

    @Test
    fun `tagWhere returns an empty fragment for a blank tag`() {
        assertThat(SearchQueryBuilder.tagWhere("")).isEmpty()
        assertThat(SearchQueryBuilder.tagWhere("   ")).isEmpty()
    }

    @Test
    fun `andWhere parenthesises both sides so a top-level OR cannot re-bind`() {
        val combined = SearchQueryBuilder.andWhere("a OR b", "c")

        assertThat(combined).isEqualTo("(a OR b) AND (c)")
    }

    @Test
    fun `andWhere returns the non-empty side when the other one is blank`() {
        assertThat(SearchQueryBuilder.andWhere(null, "x")).isEqualTo("x")
        assertThat(SearchQueryBuilder.andWhere("", "x")).isEqualTo("x")
        assertThat(SearchQueryBuilder.andWhere("a", "")).isEqualTo("a")
    }

    // ---- Data layer --------------------------------------------------------

    @Test
    fun `a chip filter narrows the results without losing the original query`() {
        // The screen was opened with a text query that matches BOTH rows.
        val baseWhere = SearchForm(ComparisonType.All).apply {
            addDescription().value = "午" // only matches lunch
        }.buildSQL()

        val tagWhere = SearchQueryBuilder.tagWhere("报销")
        val combined = SearchQueryBuilder.andWhere(baseWhere, tagWhere)

        assertThat(searchUids(combined)).containsExactly(lunch.uid)
    }

    @Test
    fun `a chip filter on a tag the row does not carry removes it from the results`() {
        // Use an "any field, any term" criterion so both rows are in the base set.
        val anyForm = SearchForm(ComparisonType.All).apply {
            addAnyField().apply {
                terms.addAll(listOf("午饭", "打车"))
                op = ComparisonType.Any
            }
        }
        val base = anyForm.buildSQL()
        assertThat(searchUids(base)).containsExactlyInAnyOrder(lunch.uid, taxi.uid)

        val combined = SearchQueryBuilder.andWhere(base, SearchQueryBuilder.tagWhere("报销"))
        assertThat(searchUids(combined)).containsExactly(lunch.uid)
    }

    @Test
    fun `a chip filter works when the screen was opened without a query`() {
        val combined = SearchQueryBuilder.andWhere(null, SearchQueryBuilder.tagWhere("出行"))

        assertThat(searchUids(combined)).containsExactly(taxi.uid)
    }

    // ---- View layer --------------------------------------------------------

    @Test
    fun `renderTags populates one chip per tag and shows the strip`() {
        val holder = newHolder()

        holder.renderTags(listOf("餐饮", "出行"))

        val container = holder.itemView.findViewById<android.widget.LinearLayout>(R.id.tag_chips)
        assertThat(container.childCount).isEqualTo(2)
        assertThat((0 until container.childCount).map { (container.getChildAt(it) as TextView).text })
            .containsExactly("#餐饮", "#出行")
        assertThat(holder.itemView.findViewById<android.view.View>(R.id.tag_chips_scroll).isVisible)
            .isTrue()
    }

    @Test
    fun `renderTags hides the strip when the transaction has no tags`() {
        val holder = newHolder()
        holder.renderTags(listOf("餐饮", "出行"))

        holder.renderTags(emptyList())

        val container = holder.itemView.findViewById<android.widget.LinearLayout>(R.id.tag_chips)
        assertThat(container.childCount).isZero()
        assertThat(holder.itemView.findViewById<android.view.View>(R.id.tag_chips_scroll).isVisible)
            .isFalse()
    }

    @Test
    fun `tapping a chip reports the raw tag name`() {
        var tapped: String? = null
        val holder = newHolder { tapped = it }

        holder.renderTags(listOf("餐饮"))
        val container = holder.itemView.findViewById<android.widget.LinearLayout>(R.id.tag_chips)
        container.getChildAt(0).performClick()

        assertThat(tapped).isEqualTo("餐饮")
    }

    // ---- helpers -----------------------------------------------------------

    private fun newHolder(onTagClick: (String) -> Unit = {}): SearchResultViewHolder {
        val themed = ContextThemeWrapper(context, R.style.Theme_GnuCash)
        val parent = FrameLayout(themed)
        val binding = CardviewTransactionBinding.inflate(
            android.view.LayoutInflater.from(themed),
            parent,
            false
        )
        return SearchResultViewHolder(binding, true, { _, _ -> }, onTagClick)
    }

    private fun save(description: String, notes: String): Transaction {
        val transaction = Transaction(description)
        transaction.notes = notes
        val split = Split(Money(BigDecimal.TEN, alphaAccount.commodity), alphaAccount)
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(bravoAccount))
        transactionsDbAdapter.addRecord(transaction)
        return transaction
    }

    private fun searchUids(where: String): List<String> {
        val cursor = transactionsDbAdapter.fetchSearch(where) ?: return emptyList()
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
}
