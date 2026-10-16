package org.gnucash.android.test.unit.db

import org.assertj.core.api.Assertions.assertThat
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * The ①b pay-off: the tag predicate produced by [org.gnucash.android.ui.search.SearchQueryBuilder]
 * actually filters real data in `transaction_tags`.
 *
 * Until feature ⑧ landed this could only be asserted as a SQL string; here the
 * tags come from saving transactions through the normal adapter path (which is
 * what the dual-write hook does), and the search runs through the real
 * `fetchSearch` query with its fixed FROM/JOIN.
 */
class TagSearchIntegrationTest : GnuCashTest() {
    private lateinit var tagsDbAdapter: TagsDbAdapter
    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var transactionsDbAdapter: TransactionsDbAdapter
    private lateinit var alphaAccount: Account
    private lateinit var bravoAccount: Account

    private lateinit var lunchWithExpense: Transaction
    private lateinit var taxi: Transaction
    private lateinit var movie: Transaction
    private lateinit var coffee: Transaction

    @Before
    fun setUp() {
        tagsDbAdapter = TagsDbAdapter.instance
        accountsDbAdapter = AccountsDbAdapter.instance
        transactionsDbAdapter = TransactionsDbAdapter.instance

        alphaAccount = Account("Search alpha")
        bravoAccount = Account("Search bravo")
        accountsDbAdapter.addRecord(bravoAccount)
        accountsDbAdapter.addRecord(alphaAccount)

        lunchWithExpense = save("午饭", "午饭 #餐饮 #报销")
        taxi = save("打车", "打车 #餐饮")
        movie = save("看电影", "看电影")
        coffee = save("咖啡", "coffee #Food")
    }

    @After
    fun tearDown() {
        transactionsDbAdapter.deleteAllRecords()
        tagsDbAdapter.deleteAllRecords()
        accountsDbAdapter.deleteAllRecords()
    }

    @Test
    fun `any tag matches every transaction carrying one of the tags`() {
        assertThat(searchUids(tagForm(ComparisonType.Any, "餐饮")))
            .containsExactlyInAnyOrder(lunchWithExpense.uid, taxi.uid)
    }

    @Test
    fun `all tags require every tag to be present`() {
        assertThat(searchUids(tagForm(ComparisonType.All, "餐饮", "报销")))
            .containsExactly(lunchWithExpense.uid)
    }

    @Test
    fun `all tags with a single tag behaves like any`() {
        assertThat(searchUids(tagForm(ComparisonType.All, "餐饮")))
            .containsExactlyInAnyOrder(lunchWithExpense.uid, taxi.uid)
    }

    @Test
    fun `none excludes transactions carrying the tag`() {
        // movie has no tags at all and coffee only carries a different one.
        assertThat(searchUids(tagForm(ComparisonType.None, "餐饮")))
            .containsExactlyInAnyOrder(movie.uid, coffee.uid)
    }

    @Test
    fun `tag matching is case insensitive`() {
        assertThat(searchUids(tagForm(ComparisonType.Any, "food")))
            .containsExactly(coffee.uid)
        assertThat(searchUids(tagForm(ComparisonType.Any, "FOOD")))
            .containsExactly(coffee.uid)
    }

    @Test
    fun `an unknown tag matches nothing`() {
        assertThat(searchUids(tagForm(ComparisonType.Any, "不存在"))).isEmpty()
    }

    @Test
    fun `any of several tags unions the matches`() {
        assertThat(searchUids(tagForm(ComparisonType.Any, "报销", "Food")))
            .containsExactlyInAnyOrder(lunchWithExpense.uid, coffee.uid)
    }

    @Test
    fun `a tag criterion combines with a text criterion under AND`() {
        val form = SearchForm(ComparisonType.All)
        form.addTag().apply {
            tags.add("餐饮")
            op = ComparisonType.Any
        }
        form.addDescription().value = "打车"

        assertThat(searchUids(form)).containsExactly(taxi.uid)
    }

    @Test
    fun `a tag criterion combines with a text criterion under OR`() {
        val form = SearchForm(ComparisonType.Any)
        form.addTag().apply {
            tags.add("报销")
            op = ComparisonType.Any
        }
        form.addDescription().value = "打车"

        assertThat(searchUids(form)).containsExactlyInAnyOrder(lunchWithExpense.uid, taxi.uid)
    }

    @Test
    fun `an empty tag criterion is ignored rather than matching everything`() {
        val form = SearchForm(ComparisonType.All)
        form.addTag() // no tags typed

        // No active criteria => empty WHERE => fetchSearch returns everything.
        assertThat(searchUids(form)).hasSize(4)
    }

    private fun tagForm(op: ComparisonType, vararg tags: String): SearchForm {
        val form = SearchForm(ComparisonType.All)
        form.addTag().apply {
            this.tags.addAll(tags)
            this.op = op
        }
        return form
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

    private fun searchUids(form: SearchForm): List<String> {
        val cursor = transactionsDbAdapter.fetchSearch(form.buildSQL()) ?: return emptyList()
        val out = mutableListOf<String>()
        // fetchSearch projects `t.*` for a filtered query and bare columns otherwise.
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
