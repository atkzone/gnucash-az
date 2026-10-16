package org.gnucash.android.test.unit.db

import android.database.sqlite.SQLiteDatabase
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseHelper
import org.gnucash.android.db.DatabaseSchema
import org.gnucash.android.db.DatabaseSchema.SearchTemplateEntry
import org.gnucash.android.db.MigrationHelper
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.SearchTemplateDbAdapter
import org.gnucash.android.db.hasTableColumn
import org.gnucash.android.model.Account
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.search.ComparisonType
import org.gnucash.android.ui.search.SearchCriteria
import org.gnucash.android.ui.search.SearchFavoritesUi
import org.gnucash.android.ui.search.SearchForm
import org.gnucash.android.ui.search.SearchTemplate
import org.gnucash.android.ui.search.SearchTemplateKind
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for the saved-search-templates persistence layer (feature ①a).
 *
 * These run against the real book database (Robolectric), so they also cover
 * the schema/migration contract: the table must exist on a freshly created
 * database of the current [DatabaseSchema.DATABASE_VERSION].
 */
class SearchTemplateDbAdapterTest : GnuCashTest() {
    private lateinit var adapter: SearchTemplateDbAdapter
    private lateinit var accountsDbAdapter: AccountsDbAdapter

    @Before
    fun setUp() {
        accountsDbAdapter = AccountsDbAdapter.instance
        adapter = SearchTemplateDbAdapter.instance
        adapter.deleteAll()
    }

    @After
    fun tearDown() {
        adapter.deleteAll()
    }

    @Test
    fun `table exists for current database version`() {
        // onCreate must have created it; otherwise every call below throws.
        assertThat(
            DatabaseHelper.hasTableColumn(
                adapter.holder.db,
                SearchTemplateEntry.TABLE_NAME,
                SearchTemplateEntry.COLUMN_FORM
            )
        ).isTrue()
        assertThat(adapter.getCount()).isZero()
    }

    @Test
    fun `insert and read back round-trips the form`() {
        val form = SearchForm(ComparisonType.Any)
        form.addAnyFieldKeywords("打车 地铁")

        val template = SearchTemplate(name = "通勤", form = form)
        val id = adapter.insert(template)

        assertThat(id).isPositive()
        assertThat(template.id).isEqualTo(id)
        assertThat(template.createdAt).isPositive()
        assertThat(template.updatedAt).isPositive()

        val reloaded = adapter.getRecord(id)
        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.name).isEqualTo("通勤")
        assertThat(reloaded.form.comparisonType).isEqualTo(ComparisonType.Any)
        // SQL is the observable contract of a restored form.
        assertThat(reloaded.form.buildSQL()).isEqualTo(form.buildSQL())
        assertThat(reloaded.createdAt).isEqualTo(template.createdAt)
    }

    @Test
    fun `multi keyword terms and per criterion op survive`() {
        val form = SearchForm(ComparisonType.All)
        val any = form.addAnyField()
        any.terms.addAll(listOf("alpha", "beta"))
        any.op = ComparisonType.Any

        val id = adapter.insert(SearchTemplate(name = "keywords", form = form))
        val criterion = adapter.getRecord(id)!!.form.criteria
            .filterIsInstance<SearchCriteria.AnyField>().single()

        assertThat(criterion.terms).containsExactly("alpha", "beta")
        assertThat(criterion.op).isEqualTo(ComparisonType.Any)
    }

    @Test
    fun `getAll orders by sort order`() {
        adapter.insert(SearchTemplate(name = "c", form = anyFieldForm("c"), sortOrder = 5))
        adapter.insert(SearchTemplate(name = "a", form = anyFieldForm("a"), sortOrder = 1))
        adapter.insert(SearchTemplate(name = "b", form = anyFieldForm("b"), sortOrder = 3))

        assertThat(adapter.getAll().map { it.name }).containsExactly("a", "b", "c")
    }

    @Test
    fun `save updates an existing row instead of adding a new one`() {
        val template = SearchTemplate(name = "saved", form = anyFieldForm("first"))
        adapter.save(template)
        assertThat(adapter.getCount()).isEqualTo(1L)

        template.form = anyFieldForm("second")
        adapter.save(template)

        assertThat(adapter.getCount()).isEqualTo(1L)
        val reloaded = adapter.getRecord(template.id)!!
        assertThat(reloaded.form.buildSQL()).isEqualTo(anyFieldForm("second").buildSQL())
    }

    @Test
    fun `save inserts when the row no longer exists`() {
        val template = SearchTemplate(name = "ghost", form = anyFieldForm("x"), id = 999_999L)
        // update() matches nothing -> must fall back to an insert, not lose the save.
        adapter.save(template)

        assertThat(adapter.getCount()).isEqualTo(1L)
        assertThat(template.id).isNotEqualTo(999_999L)
        assertThat(adapter.getRecord(template.id)?.name).isEqualTo("ghost")
    }

    @Test
    fun `getRecordByName finds the template`() {
        adapter.insert(SearchTemplate(name = "unique-name", form = anyFieldForm("x")))
        assertThat(adapter.getRecordByName("unique-name")).isNotNull()
        assertThat(adapter.getRecordByName("missing-name")).isNull()
    }

    @Test
    fun `next sort order appends`() {
        assertThat(adapter.nextSortOrder()).isZero()
        adapter.insert(SearchTemplate(name = "a", form = anyFieldForm("a"), sortOrder = 0))
        adapter.insert(SearchTemplate(name = "b", form = anyFieldForm("b"), sortOrder = 1))
        assertThat(adapter.nextSortOrder()).isEqualTo(2)
    }

    @Test
    fun `delete removes a single row and deleteAll empties the table`() {
        val first = SearchTemplate(name = "a", form = anyFieldForm("a"))
        val second = SearchTemplate(name = "b", form = anyFieldForm("b"))
        adapter.insert(first)
        adapter.insert(second)

        assertThat(adapter.delete(first.id)).isEqualTo(1)
        assertThat(adapter.getCount()).isEqualTo(1L)
        assertThat(adapter.getRecord(second.id)).isNotNull()

        assertThat(adapter.deleteAll()).isEqualTo(1)
        assertThat(adapter.getCount()).isZero()
    }

    @Test
    fun `account criterion keeps its uid so the filter is never silently dropped`() {
        // An account uid that cannot be resolved to an Account row.
        val form = SearchForm(ComparisonType.All)
        form.addAccount().uid = "unknown-account-uid"

        val id = adapter.insert(SearchTemplate(name = "by account", form = form))
        val criterion = adapter.getRecord(id)!!.form.criteria
            .filterIsInstance<SearchCriteria.Account>().single()

        assertThat(criterion.resolvedUid()).isEqualTo("unknown-account-uid")
        assertThat(criterion.isEmpty()).isFalse()
        assertThat(adapter.getRecord(id)!!.form.buildSQL()).contains("unknown-account-uid")
    }

    @Test
    fun `account criterion resolves a real account`() {
        val account = Account("Test account")
        accountsDbAdapter.addRecord(account)

        val form = SearchForm(ComparisonType.All)
        form.addAccount().value = account

        val id = adapter.insert(SearchTemplate(name = "real account", form = form))
        val criterion = adapter.getRecord(id)!!.form.criteria
            .filterIsInstance<SearchCriteria.Account>().single()

        assertThat(criterion.resolvedUid()).isEqualTo(account.uid)
        assertThat(adapter.getRecord(id)!!.form.buildSQL()).contains(account.uid)

        accountsDbAdapter.deleteRecord(account.uid)
    }

    @Test
    fun `an undecodable payload is listed but yields an empty form`() {
        // Simulate a corrupt/legacy row written outside the codec.
        adapter.holder.db.execSQL(
            "INSERT INTO ${SearchTemplateEntry.TABLE_NAME} " +
                    "(${SearchTemplateEntry.COLUMN_NAME}, ${SearchTemplateEntry.COLUMN_FORM}, " +
                    "${SearchTemplateEntry.COLUMN_SORT_ORDER}) VALUES ('broken', 'not-json', 0)"
        )

        val templates = adapter.getAll()
        assertThat(templates).hasSize(1)
        assertThat(templates.single().name).isEqualTo("broken")
        // Degrades to "no criteria" rather than throwing; the UI refuses to apply it.
        assertThat(templates.single().form.criteria).isEmpty()
    }

    // ---- 常用搜索: kind scoping, bulk delete, the real save path ----------------

    @Test
    fun `a full text favourite round trips through the table`() {
        val template = SearchTemplate(
            name = "通勤",
            kind = SearchTemplateKind.FullText,
            query = "打车 | 地铁"
        )
        val id = adapter.insert(template)

        val reloaded = adapter.getRecord(id)!!
        assertThat(reloaded.kind).isEqualTo(SearchTemplateKind.FullText)
        assertThat(reloaded.query).isEqualTo("打车 | 地铁")
        // A full-text entry carries no criteria: the advanced screen must not be
        // able to apply it as "match everything".
        assertThat(reloaded.form.criteria).isEmpty()
    }

    @Test
    fun `getByKind keeps the two screens' lists apart`() {
        adapter.insert(SearchTemplate(name = "form-a", form = anyFieldForm("a")))
        adapter.insert(
            SearchTemplate(name = "full-a", kind = SearchTemplateKind.FullText, query = "a")
        )
        adapter.insert(
            SearchTemplate(name = "full-b", kind = SearchTemplateKind.FullText, query = "b")
        )

        assertThat(adapter.getByKind(SearchTemplateKind.Form).map { it.name })
            .containsExactly("form-a")
        assertThat(adapter.getByKind(SearchTemplateKind.FullText).map { it.name })
            .containsExactlyInAnyOrder("full-a", "full-b")
    }

    @Test
    fun `bulk delete removes exactly the selected rows`() {
        val first = SearchTemplate(name = "a", form = anyFieldForm("a"))
        val second = SearchTemplate(name = "b", form = anyFieldForm("b"))
        val third = SearchTemplate(name = "c", form = anyFieldForm("c"))
        adapter.insert(first)
        adapter.insert(second)
        adapter.insert(third)

        assertThat(adapter.delete(listOf(first.id, third.id))).isEqualTo(2)
        assertThat(adapter.getAll().map { it.name }).containsExactly("b")
    }

    @Test
    fun `bulk delete with an empty selection is a no-op`() {
        adapter.insert(SearchTemplate(name = "a", form = anyFieldForm("a")))
        // Must not blow up (a `WHERE _id IN ()` would be a syntax error).
        assertThat(adapter.delete(emptyList())).isZero()
        assertThat(adapter.getCount()).isEqualTo(1L)
    }

    @Test
    fun `saving under the same name overwrites instead of duplicating`() {
        assertThat(
            SearchFavoritesUi.save(context, SearchTemplate(name = "same", form = anyFieldForm("first")))
        ).isTrue()
        assertThat(
            SearchFavoritesUi.save(context, SearchTemplate(name = "same", form = anyFieldForm("second")))
        ).isTrue()

        assertThat(adapter.getCount()).isEqualTo(1L)
        assertThat(adapter.getRecordByName("same")!!.form.buildSQL())
            .isEqualTo(anyFieldForm("second").buildSQL())
    }

    @Test
    fun `the same name on the two screens does not collide`() {
        SearchFavoritesUi.save(context, SearchTemplate(name = "同名", form = anyFieldForm("x")))
        SearchFavoritesUi.save(context, SearchFavoritesUi.fullTextTemplate("同名", "y"))

        assertThat(adapter.getCount()).isEqualTo(2L)
        assertThat(adapter.getByKind(SearchTemplateKind.Form).single().name).isEqualTo("同名")
        assertThat(adapter.getByKind(SearchTemplateKind.FullText).single().query).isEqualTo("y")
    }

    private fun anyFieldForm(term: String): SearchForm {
        val form = SearchForm(ComparisonType.All)
        form.addAnyFieldKeywords(term)
        return form
    }
}

/**
 * Migration contract for saved search templates (feature ①a): a pure additive
 * table, safe to run against an older database and safe to re-run.
 *
 * The constant assertion tracks [DatabaseSchema.DATABASE_VERSION], which now also
 * covers the tag tables (feature ⑧, version 32), the sync tables (feature ②
 * v2, version 33), the sync-base etag/pending columns (version 34), the
 * local-only saved-transaction-template table (feature ①d, version 35) and the
 * conflict-resolution override table (feature ② v3, version 36).
 */
class SearchTemplateMigrationTest : GnuCashTest() {

    @Test
    fun `database version is 36`() {
        assertThat(DatabaseSchema.DATABASE_VERSION).isEqualTo(36)
    }

    @Test
    fun `migrate to the current version creates the table and is idempotent`() {
        val file = File(context.cacheDir, "search_template_migration_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            assertThat(DatabaseHelper.hasTableColumn(db, SearchTemplateEntry.TABLE_NAME, SearchTemplateEntry.COLUMN_FORM))
                .`as`("table must not exist before the migration")
                .isFalse()

            MigrationHelper.migrate(context, db, 30, 31)

            assertThat(db.hasTableColumn(SearchTemplateEntry.TABLE_NAME, SearchTemplateEntry.COLUMN_FORM)).isTrue()
            assertThat(db.hasTableColumn(SearchTemplateEntry.TABLE_NAME, SearchTemplateEntry.COLUMN_NAME)).isTrue()
            assertThat(db.hasTableColumn(SearchTemplateEntry.TABLE_NAME, SearchTemplateEntry.COLUMN_SORT_ORDER)).isTrue()
            assertThat(db.hasTableColumn(SearchTemplateEntry.TABLE_NAME, SearchTemplateEntry.COLUMN_CREATED_AT)).isTrue()
            assertThat(db.hasTableColumn(SearchTemplateEntry.TABLE_NAME, SearchTemplateEntry.COLUMN_UPDATED_AT)).isTrue()

            // Re-running must be a no-op, not an error (CREATE TABLE IF NOT EXISTS).
            MigrationHelper.migrate(context, db, 30, 31)

            // And the table is actually usable.
            db.execSQL(
                "INSERT INTO ${SearchTemplateEntry.TABLE_NAME} " +
                        "(${SearchTemplateEntry.COLUMN_NAME}, ${SearchTemplateEntry.COLUMN_FORM}, " +
                        "${SearchTemplateEntry.COLUMN_SORT_ORDER}) VALUES ('t', '{}', 0)"
            )
            db.rawQuery("SELECT COUNT(*) FROM ${SearchTemplateEntry.TABLE_NAME}", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(1L)
            }
        } finally {
            db.close()
            file.delete()
        }
    }
}
