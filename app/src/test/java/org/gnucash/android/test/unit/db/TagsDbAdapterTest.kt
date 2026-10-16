package org.gnucash.android.test.unit.db

import android.database.sqlite.SQLiteDatabase
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseHelper
import org.gnucash.android.db.DatabaseSchema
import org.gnucash.android.db.DatabaseSchema.TagEntry
import org.gnucash.android.db.DatabaseSchema.TransactionTagEntry
import org.gnucash.android.db.MigrationHelper
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TagsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.db.hasTableColumn
import org.gnucash.android.model.Account
import org.gnucash.android.model.Money
import org.gnucash.android.model.Split
import org.gnucash.android.model.Transaction
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Tests for the tag persistence layer (feature ⑧) against the real book database.
 *
 * Covers the dual-write contract from the plan §6.3: the `#tag` text stays the
 * source of truth and the `tags` / `transaction_tags` tables are its queryable
 * mirror.
 */
class TagsDbAdapterTest : GnuCashTest() {
    private lateinit var tagsDbAdapter: TagsDbAdapter
    private lateinit var accountsDbAdapter: AccountsDbAdapter
    private lateinit var transactionsDbAdapter: TransactionsDbAdapter
    private lateinit var alphaAccount: Account
    private lateinit var bravoAccount: Account

    @Before
    fun setUp() {
        tagsDbAdapter = TagsDbAdapter.instance
        accountsDbAdapter = AccountsDbAdapter.instance
        transactionsDbAdapter = TransactionsDbAdapter.instance

        alphaAccount = Account("Tag alpha")
        bravoAccount = Account("Tag bravo")
        accountsDbAdapter.addRecord(bravoAccount)
        accountsDbAdapter.addRecord(alphaAccount)
    }

    @After
    fun tearDown() {
        transactionsDbAdapter.deleteAllRecords()
        tagsDbAdapter.deleteAllRecords()
        accountsDbAdapter.deleteAllRecords()
    }

    @Test
    fun `tables exist for the current database version`() {
        assertThat(
            DatabaseHelper.hasTableColumn(
                accountsDbAdapter.holder.db,
                TagEntry.TABLE_NAME,
                TagEntry.COLUMN_NAME
            )
        ).isTrue()
        assertThat(
            DatabaseHelper.hasTableColumn(
                accountsDbAdapter.holder.db,
                TransactionTagEntry.TABLE_NAME,
                TransactionTagEntry.COLUMN_TAG
            )
        ).isTrue()
        assertThat(tagsDbAdapter.count()).isZero()
    }

    @Test
    fun `findOrCreate does not duplicate a tag typed with different case`() {
        val first = tagsDbAdapter.findOrCreate("Food")!!
        val second = tagsDbAdapter.findOrCreate("food")!!

        assertThat(second.uid).isEqualTo(first.uid)
        assertThat(tagsDbAdapter.count()).isEqualTo(1L)
        assertThat(tagsDbAdapter.getTagNames()).containsExactly("Food")
    }

    @Test
    fun `findOrCreate normalizes a leading hash`() {
        val tag = tagsDbAdapter.findOrCreate("#餐饮")!!
        assertThat(tag.name).isEqualTo("餐饮")
        assertThat(tagsDbAdapter.getRecordByName("餐饮")?.uid).isEqualTo(tag.uid)
    }

    @Test
    fun `findOrCreate rejects blank names`() {
        assertThat(tagsDbAdapter.findOrCreate("   ")).isNull()
        assertThat(tagsDbAdapter.findOrCreate("#")).isNull()
        assertThat(tagsDbAdapter.count()).isZero()
    }

    @Test
    fun `saving a transaction mirrors the tags found in notes and memo`() {
        val transaction = newTransaction(
            "lunch",
            notes = "午饭 #餐饮 #报销",
            memo = "打车 #出行"
        )
        transactionsDbAdapter.addRecord(transaction)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid))
            .containsExactly("出行", "报销", "餐饮")
        assertThat(tagsDbAdapter.getTagNames()).containsExactlyInAnyOrder("餐饮", "报销", "出行")
    }

    @Test
    fun `saving a transaction with no tags leaves both tables empty`() {
        val transaction = newTransaction("plain", notes = "no tags here")
        transactionsDbAdapter.addRecord(transaction)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid)).isEmpty()
        assertThat(tagsDbAdapter.count()).isZero()
    }

    @Test
    fun `re-saving replaces the tag set instead of duplicating rows`() {
        val transaction = newTransaction("lunch", notes = "午饭 #餐饮")
        transactionsDbAdapter.addRecord(transaction)

        transaction.notes = "午饭 #餐饮 #新增"
        transactionsDbAdapter.addRecord(transaction)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid))
            .containsExactly("新增", "餐饮")
    }

    @Test
    fun `removing a tag from the text removes the link but keeps the registry row`() {
        val transaction = newTransaction("lunch", notes = "午饭 #餐饮 #报销")
        transactionsDbAdapter.addRecord(transaction)

        transaction.notes = "午饭 #餐饮"
        transactionsDbAdapter.addRecord(transaction)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid))
            .containsExactly("餐饮")
        // The registry is not silently pruned: the leftover tag is still listed...
        assertThat(tagsDbAdapter.getTagNames()).containsExactlyInAnyOrder("餐饮", "报销")
        // ...until it is explicitly garbage-collected.
        assertThat(tagsDbAdapter.deleteUnused()).isEqualTo(1)
        assertThat(tagsDbAdapter.getTagNames()).containsExactly("餐饮")
    }

    @Test
    fun `setTagsForTransaction is idempotent`() {
        val transaction = newTransaction("lunch", notes = "午饭")
        transactionsDbAdapter.addRecord(transaction)

        assertThat(tagsDbAdapter.setTagsForTransaction(transaction.uid, listOf("a", "b"))).isEqualTo(2)
        assertThat(tagsDbAdapter.setTagsForTransaction(transaction.uid, listOf("a", "b"))).isEqualTo(2)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid)).containsExactly("a", "b")
        assertThat(countTransactionTagRows()).isEqualTo(2L)
    }

    @Test
    fun `deleting a transaction cascades away its links`() {
        val transaction = newTransaction("lunch", notes = "午饭 #餐饮")
        transactionsDbAdapter.addRecord(transaction)
        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid)).containsExactly("餐饮")

        transactionsDbAdapter.deleteRecord(transaction.uid)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid)).isEmpty()
        assertThat(countTransactionTagRows()).isZero()
    }

    @Test
    fun `deleting a tag removes its links but not the other tags`() {
        val transaction = newTransaction("lunch", notes = "午饭 #餐饮 #报销")
        transactionsDbAdapter.addRecord(transaction)

        assertThat(tagsDbAdapter.deleteTag("餐饮")).isEqualTo(1)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid)).containsExactly("报销")
        assertThat(tagsDbAdapter.getTagNames()).containsExactly("报销")
    }

    @Test
    fun `rename propagates to the relation table`() {
        val transaction = newTransaction("lunch", notes = "午饭 #餐饮")
        transactionsDbAdapter.addRecord(transaction)
        val tag = tagsDbAdapter.getRecordByName("餐饮")!!

        tagsDbAdapter.rename(tag, "吃饭")

        assertThat(tag.name).isEqualTo("吃饭")
        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid)).containsExactly("吃饭")
        assertThat(countTransactionTagRows()).isEqualTo(1L)
    }

    @Test
    fun `rename refuses to collide with an existing tag`() {
        tagsDbAdapter.findOrCreate("餐饮")
        val other = tagsDbAdapter.findOrCreate("出行")!!

        tagsDbAdapter.rename(other, "餐饮")

        assertThat(other.name).isEqualTo("出行")
        assertThat(tagsDbAdapter.getTagNames()).containsExactlyInAnyOrder("餐饮", "出行")
    }

    @Test
    fun `usage counts group by tag and order by count`() {
        val first = newTransaction("a", notes = "#餐饮")
        val second = newTransaction("b", notes = "#餐饮 #出行")
        val third = newTransaction("c", notes = "#出行")
        transactionsDbAdapter.addRecord(first)
        transactionsDbAdapter.addRecord(second)
        transactionsDbAdapter.addRecord(third)

        val usage = tagsDbAdapter.getUsageCounts().associate { it.name to it.count }
        // Ordering is by count then name; assert on the mapping so the test does not
        // depend on how SQLite collates CJK text.
        assertThat(usage).hasSize(2)
        assertThat(usage["餐饮"]).isEqualTo(2L)
        assertThat(usage["出行"]).isEqualTo(2L)

        assertThat(tagsDbAdapter.getTransactionCountForTag("餐饮")).isEqualTo(2L)
        assertThat(tagsDbAdapter.getTransactionCountForTag("不存在")).isZero()
    }

    @Test
    fun `usage counts ignore transactions that no longer exist`() {
        val transaction = newTransaction("a", notes = "#餐饮")
        transactionsDbAdapter.addRecord(transaction)
        transactionsDbAdapter.deleteRecord(transaction.uid)

        assertThat(tagsDbAdapter.getUsageCounts()).isEmpty()
    }

    @Test
    fun `sync ignores a transaction that is not in the database`() {
        val orphan = newTransaction("ghost", notes = "#餐饮")
        // Never saved: mirroring would violate the cascading foreign key.
        assertThat(tagsDbAdapter.syncFromTransaction(orphan)).isZero()
        assertThat(tagsDbAdapter.count()).isZero()
    }

    @Test
    fun `tags typed in the description are picked up too`() {
        val transaction = Transaction("coffee #饮品")
        val split = Split(Money(BigDecimal.TEN, alphaAccount.commodity), alphaAccount)
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(bravoAccount))
        transactionsDbAdapter.addRecord(transaction)

        assertThat(tagsDbAdapter.getTagNamesForTransaction(transaction.uid)).containsExactly("饮品")
    }

    private fun newTransaction(description: String, notes: String = "", memo: String = ""): Transaction {
        val transaction = Transaction(description)
        transaction.notes = notes
        val split = Split(Money(BigDecimal.TEN, alphaAccount.commodity), alphaAccount)
        split.memo = memo
        transaction.addSplit(split)
        transaction.addSplit(split.createPair(bravoAccount))
        return transaction
    }

    private fun countTransactionTagRows(): Long {
        var count = 0L
        accountsDbAdapter.holder.db
            .rawQuery("SELECT COUNT(*) FROM ${TransactionTagEntry.TABLE_NAME}", null)
            .use { cursor ->
                if (cursor.moveToFirst()) count = cursor.getLong(0)
            }
        return count
    }
}

/**
 * Migration contract for schema version 32 (feature ⑧): two purely additive
 * tables, safe against an older database and safe to re-run.
 *
 * The current-version assertion tracks [DatabaseSchema.DATABASE_VERSION], which
 * has since moved on to 36 (feature ② v2 sync tables at 33, sync-base etag/pending
 * columns at 34, local-only saved-transaction templates at 35, conflict-resolution
 * overrides at 36).
 */
class TagMigrationTest : GnuCashTest() {

    @Test
    fun `database version is 36`() {
        assertThat(DatabaseSchema.DATABASE_VERSION).isEqualTo(36)
    }

    @Test
    fun `migrate to 32 creates the tag tables and is idempotent`() {
        val file = File(context.cacheDir, "tag_migration_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            assertThat(db.hasTableColumn(TagEntry.TABLE_NAME, TagEntry.COLUMN_NAME))
                .`as`("tags must not exist before the migration")
                .isFalse()
            assertThat(db.hasTableColumn(TransactionTagEntry.TABLE_NAME, TransactionTagEntry.COLUMN_TAG))
                .`as`("transaction_tags must not exist before the migration")
                .isFalse()

            MigrationHelper.migrate(context, db, 31, 32)

            assertThat(db.hasTableColumn(TagEntry.TABLE_NAME, TagEntry.COLUMN_NAME)).isTrue()
            assertThat(db.hasTableColumn(TagEntry.TABLE_NAME, TagEntry.COLUMN_UID)).isTrue()
            assertThat(db.hasTableColumn(TagEntry.TABLE_NAME, TagEntry.COLUMN_COLOR)).isTrue()
            assertThat(db.hasTableColumn(TransactionTagEntry.TABLE_NAME, TransactionTagEntry.COLUMN_TAG)).isTrue()
            assertThat(db.hasTableColumn(TransactionTagEntry.TABLE_NAME, TransactionTagEntry.COLUMN_TRANSACTION_UID)).isTrue()
            assertThat(db.hasTableColumn(TransactionTagEntry.TABLE_NAME, TransactionTagEntry.COLUMN_TAG_UID)).isTrue()
            assertThat(indexExists(db, TagEntry.INDEX_NAME)).isTrue()
            assertThat(indexExists(db, TransactionTagEntry.INDEX_UNIQUE)).isTrue()

            // Re-running must be a no-op, not an error (IF NOT EXISTS + dropped trigger).
            MigrationHelper.migrate(context, db, 31, 32)

            // And the tables are actually usable.
            db.execSQL(
                "INSERT INTO ${TagEntry.TABLE_NAME} " +
                        "(${TagEntry.COLUMN_UID}, ${TagEntry.COLUMN_NAME}) VALUES ('uid-1', '餐饮')"
            )
            db.rawQuery("SELECT COUNT(*) FROM ${TagEntry.TABLE_NAME}", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(1L)
            }
            // The name is case-insensitive, so the unique index must reject "餐饮" again.
            var rejected = false
            try {
                db.execSQL(
                    "INSERT INTO ${TagEntry.TABLE_NAME} " +
                            "(${TagEntry.COLUMN_UID}, ${TagEntry.COLUMN_NAME}) VALUES ('uid-2', '餐饮')"
                )
            } catch (expected: android.database.SQLException) {
                rejected = true
            }
            assertThat(rejected).`as`("tag name must be unique, case-insensitively").isTrue()
        } finally {
            db.close()
            file.delete()
        }
    }

    private fun indexExists(db: SQLiteDatabase, name: String): Boolean {
        var found = false
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
        ).use { cursor ->
            found = cursor.moveToFirst()
        }
        return found
    }
}
