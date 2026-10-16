package org.gnucash.android.test.unit.db

import android.database.sqlite.SQLiteDatabase
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseHelper
import org.gnucash.android.db.DatabaseSchema
import org.gnucash.android.db.DatabaseSchema.TransactionTemplateEntry
import org.gnucash.android.db.MigrationHelper
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TransactionTemplateDbAdapter
import org.gnucash.android.db.hasTableColumn
import org.gnucash.android.test.unit.GnuCashTest
import org.gnucash.android.ui.template.TransactionTemplate
import org.gnucash.android.ui.template.TransactionTemplateCodec
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Tests for the saved-transaction-template persistence layer (feature ①d).
 *
 * These run against the real book database (Robolectric), so they also cover the
 * schema contract: the table must exist on a freshly created database of the
 * current [DatabaseSchema.DATABASE_VERSION].
 */
class TransactionTemplateDbAdapterTest : GnuCashTest() {

    private lateinit var adapter: TransactionTemplateDbAdapter

    @Before
    fun setUp() {
        adapter = TransactionTemplateDbAdapter.instance
        adapter.deleteAll()
    }

    @After
    fun tearDown() {
        adapter.deleteAll()
    }

    @Test
    fun `table exists for the current database version`() {
        assertThat(
            DatabaseHelper.hasTableColumn(
                adapter.holder.db,
                TransactionTemplateEntry.TABLE_NAME,
                TransactionTemplateEntry.COLUMN_PAYLOAD
            )
        ).isTrue()
        assertThat(adapter.getCount()).isZero()
    }

    @Test
    fun `insert and read back round-trips every field including a blank amount`() {
        val template = TransactionTemplate(
            name = "买菜",
            debitAccountUid = "expense-uid",
            creditAccountUid = "cash-uid",
            amount = null,
            description = "超市",
            debitMemo = "借方"
        )

        val id = adapter.insert(template)
        assertThat(id).isPositive()
        assertThat(template.id).isEqualTo(id)
        assertThat(template.createdAt).isPositive()

        val loaded = adapter.getRecord(id)
        assertThat(loaded).isNotNull()
        requireNotNull(loaded)
        assertThat(loaded.name).isEqualTo("买菜")
        assertThat(loaded.debitAccountUid).isEqualTo("expense-uid")
        assertThat(loaded.creditAccountUid).isEqualTo("cash-uid")
        assertThat(loaded.amount).isNull()
        assertThat(loaded.description).isEqualTo("超市")
        assertThat(loaded.debitMemo).isEqualTo("借方")
        assertThat(loaded.decodeFailed).isFalse()
    }

    @Test
    fun `a fixed amount survives the database round trip`() {
        val id = adapter.insert(
            TransactionTemplate(
                name = "房租",
                debitAccountUid = "a",
                creditAccountUid = "b",
                amount = BigDecimal("3500.00")
            )
        )

        val loaded = requireNotNull(adapter.getRecord(id))
        assertThat(loaded.amount).isEqualByComparingTo(BigDecimal("3500.00"))
    }

    @Test
    fun `update rewrites the stored payload`() {
        val id = adapter.insert(TransactionTemplate(name = "t", debitAccountUid = "a", creditAccountUid = "b"))

        val loaded = requireNotNull(adapter.getRecord(id))
        loaded.amount = BigDecimal("9.99")
        loaded.notes = "改过"
        assertThat(adapter.update(loaded)).isEqualTo(1)

        val again = requireNotNull(adapter.getRecord(id))
        assertThat(again.amount).isEqualByComparingTo(BigDecimal("9.99"))
        assertThat(again.notes).isEqualTo("改过")
    }

    @Test
    fun `saveByName overwrites the entry with the same name instead of adding a second one`() {
        adapter.saveByName(TransactionTemplate(name = "通勤", debitAccountUid = "a", creditAccountUid = "b"))
        val countAfterFirst = adapter.getCount()

        val again = TransactionTemplate(name = "通勤", debitAccountUid = "a", creditAccountUid = "b")
        again.amount = BigDecimal("12.5")
        val id = adapter.saveByName(again)

        assertThat(adapter.getCount()).`as`("same name must overwrite").isEqualTo(countAfterFirst)
        assertThat(id).isEqualTo(requireNotNull(adapter.getRecordByName("通勤")).id)
        assertThat(requireNotNull(adapter.getRecordByName("通勤")).amount)
            .isEqualByComparingTo(BigDecimal("12.5"))
    }

    @Test
    fun `saveByName appends a brand new name`() {
        adapter.saveByName(TransactionTemplate(name = "一", debitAccountUid = "a", creditAccountUid = "b"))
        adapter.saveByName(TransactionTemplate(name = "二", debitAccountUid = "a", creditAccountUid = "b"))

        assertThat(adapter.getCount()).isEqualTo(2L)
        assertThat(adapter.getAll().map { it.name }).containsExactly("一", "二")
    }

    @Test
    fun `bulk delete removes exactly the selected rows`() {
        val a = adapter.insert(TransactionTemplate(name = "a", debitAccountUid = "x", creditAccountUid = "y"))
        val b = adapter.insert(TransactionTemplate(name = "b", debitAccountUid = "x", creditAccountUid = "y"))
        val c = adapter.insert(TransactionTemplate(name = "c", debitAccountUid = "x", creditAccountUid = "y"))

        assertThat(adapter.delete(listOf(a, c))).isEqualTo(2)
        assertThat(adapter.getAll().map { it.id }).containsExactly(b)
    }

    @Test
    fun `bulk delete with an empty selection is a no-op`() {
        adapter.insert(TransactionTemplate(name = "a", debitAccountUid = "x", creditAccountUid = "y"))
        assertThat(adapter.delete(emptyList())).isZero()
        assertThat(adapter.getCount()).isEqualTo(1L)
    }

    @Test
    fun `a corrupt payload keeps the row but marks it unusable`() {
        // Simulate a payload written by a future/broken version.
        adapter.holder.db.execSQL(
            "INSERT INTO ${TransactionTemplateEntry.TABLE_NAME} " +
                    "(${TransactionTemplateEntry.COLUMN_NAME}, ${TransactionTemplateEntry.COLUMN_PAYLOAD}, " +
                    "${TransactionTemplateEntry.COLUMN_SORT_ORDER}) VALUES ('坏模板', 'not json', 0)"
        )

        val all = adapter.getAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].name).isEqualTo("坏模板")
        assertThat(all[0].decodeFailed).isTrue()
        assertThat(all[0].isUsable).`as`("a corrupt template must never be applied").isFalse()

        // …and it can still be deleted, so the user is never stuck with it.
        assertThat(adapter.delete(all[0].id)).isEqualTo(1)
        assertThat(adapter.getCount()).isZero()
    }

    @Test
    fun `nextSortOrder grows monotonically`() {
        assertThat(adapter.nextSortOrder()).isZero()
        adapter.saveByName(TransactionTemplate(name = "a", debitAccountUid = "x", creditAccountUid = "y"))
        adapter.saveByName(TransactionTemplate(name = "b", debitAccountUid = "x", creditAccountUid = "y"))
        assertThat(adapter.nextSortOrder()).isEqualTo(2)

        val orders = adapter.getAll().map { it.sortOrder }
        assertThat(orders).containsExactly(0, 1)
    }

    @Test
    fun `getAll is ordered by sort order`() {
        val second = TransactionTemplate(name = "乙", debitAccountUid = "x", creditAccountUid = "y")
        second.sortOrder = 5
        adapter.insert(second)
        val first = TransactionTemplate(name = "甲", debitAccountUid = "x", creditAccountUid = "y")
        first.sortOrder = 1
        adapter.insert(first)

        assertThat(adapter.getAll().map { it.name }).containsExactly("甲", "乙")
    }

    @Test
    fun `update rejects a template that was never persisted`() {
        try {
            adapter.update(TransactionTemplate(name = "x"))
            throw AssertionError("expected update() to reject a row without an id")
        } catch (expected: IllegalArgumentException) {
            // expected: require(id > 0)
        }
    }

    @Test
    fun `an empty store has no templates`() {
        assertThat(adapter.getAll()).isEmpty()
        assertThat(adapter.getRecord(1L)).isNull()
        assertThat(adapter.getRecordByName("nope")).isNull()
    }

    @Test
    fun `accounts adapter is only needed for the instance accessor`() {
        // Guards the lazy-instance contract: the adapter must follow a book switch
        // rather than pinning the database at class-init time.
        assertThat(AccountsDbAdapter.instance).isNotNull()
        assertThat(TransactionTemplateDbAdapter.instance.holder).isSameAs(AccountsDbAdapter.instance.holder)
    }
}

/**
 * Migration contract for the saved-transaction templates (feature ①d): a purely
 * additive table, safe to run against an older database and safe to re-run.
 *
 * The constant assertion tracks [DatabaseSchema.DATABASE_VERSION], which has
 * moved on to 36 (tag tables at 32, sync tables at 33, sync-base etag/pending
 * columns at 34, conflict-resolution overrides at 36).
 */
class TransactionTemplateMigrationTest : GnuCashTest() {

    @Test
    fun `database version is 36`() {
        assertThat(DatabaseSchema.DATABASE_VERSION).isEqualTo(36)
    }

    @Test
    fun `migrate to 35 creates the table and is idempotent`() {
        val file = File(context.cacheDir, "transaction_template_migration_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            assertThat(
                DatabaseHelper.hasTableColumn(
                    db,
                    TransactionTemplateEntry.TABLE_NAME,
                    TransactionTemplateEntry.COLUMN_PAYLOAD
                )
            ).`as`("table must not exist before the migration").isFalse()

            MigrationHelper.migrate(context, db, 34, 35)

            assertThat(db.hasTableColumn(TransactionTemplateEntry.TABLE_NAME, TransactionTemplateEntry.COLUMN_NAME)).isTrue()
            assertThat(db.hasTableColumn(TransactionTemplateEntry.TABLE_NAME, TransactionTemplateEntry.COLUMN_PAYLOAD)).isTrue()
            assertThat(db.hasTableColumn(TransactionTemplateEntry.TABLE_NAME, TransactionTemplateEntry.COLUMN_SORT_ORDER)).isTrue()
            assertThat(db.hasTableColumn(TransactionTemplateEntry.TABLE_NAME, TransactionTemplateEntry.COLUMN_CREATED_AT)).isTrue()
            assertThat(db.hasTableColumn(TransactionTemplateEntry.TABLE_NAME, TransactionTemplateEntry.COLUMN_UPDATED_AT)).isTrue()

            // Re-running must be a no-op, not an error (CREATE TABLE IF NOT EXISTS).
            MigrationHelper.migrate(context, db, 34, 35)

            // And the table is actually usable end-to-end through the codec.
            val payload = TransactionTemplateCodec.encode(
                TransactionTemplate(name = "t", debitAccountUid = "a", creditAccountUid = "b")
            )
            db.execSQL(
                "INSERT INTO ${TransactionTemplateEntry.TABLE_NAME} " +
                        "(${TransactionTemplateEntry.COLUMN_NAME}, ${TransactionTemplateEntry.COLUMN_PAYLOAD}, " +
                        "${TransactionTemplateEntry.COLUMN_SORT_ORDER}) VALUES ('t', '$payload', 0)"
            )
            db.rawQuery("SELECT COUNT(*) FROM ${TransactionTemplateEntry.TABLE_NAME}", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(1L)
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun `migrating an older database all the way up creates the table too`() {
        val file = File(context.cacheDir, "transaction_template_full_migration_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            // Jumping from an old version must still end with the table present:
            // the migration is chained, not only reachable from 34.
            MigrationHelper.migrate(context, db, 30, DatabaseSchema.DATABASE_VERSION)
            assertThat(db.hasTableColumn(TransactionTemplateEntry.TABLE_NAME, TransactionTemplateEntry.COLUMN_PAYLOAD))
                .isTrue()
        } finally {
            db.close()
            file.delete()
        }
    }
}
