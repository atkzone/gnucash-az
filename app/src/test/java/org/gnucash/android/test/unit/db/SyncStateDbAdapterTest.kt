package org.gnucash.android.test.unit.db

import android.database.sqlite.SQLiteDatabase
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseHelper
import org.gnucash.android.db.DatabaseSchema
import org.gnucash.android.db.DatabaseSchema.SyncResolutionEntry
import org.gnucash.android.db.DatabaseSchema.SyncStateEntry
import org.gnucash.android.db.DatabaseSchema.TombstoneEntry
import org.gnucash.android.db.MigrationHelper
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.db.hasTableColumn
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for the sync persistence layer (feature ② v2) against the real book
 * database.
 *
 * Covers the two constraints from plan §7.3: the snapshot and its watermark are
 * read together, and a deletion is only ever recorded as *evidence* (a tombstone),
 * never inferred from a row simply not being there.
 */
class SyncStateDbAdapterTest : GnuCashTest() {
    private lateinit var syncStateDbAdapter: SyncStateDbAdapter
    private lateinit var accountsDbAdapter: AccountsDbAdapter

    @Before
    fun setUp() {
        syncStateDbAdapter = SyncStateDbAdapter.instance
        accountsDbAdapter = AccountsDbAdapter.instance
    }

    @After
    fun tearDown() {
        syncStateDbAdapter.deleteAllRecords()
        val db = accountsDbAdapter.holder.db
        db.delete(TombstoneEntry.TABLE_NAME, null, null)
        db.delete(SyncResolutionEntry.TABLE_NAME, null, null)
    }

    // -------------------------------------------------------------- base rows

    @Test
    fun `tables exist for the current database version`() {
        val db = accountsDbAdapter.holder.db
        assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_RECORD_KEY))
            .isTrue()
        assertThat(
            db.hasTableColumn(
                SyncStateEntry.TABLE_NAME,
                SyncStateEntry.COLUMN_BASE_MODIFIED_AT
            )
        ).isTrue()
        assertThat(db.hasTableColumn(TombstoneEntry.TABLE_NAME, TombstoneEntry.COLUMN_DELETED_AT))
            .isTrue()
        assertThat(
            db.hasTableColumn(SyncResolutionEntry.TABLE_NAME, SyncResolutionEntry.COLUMN_SIDE)
        ).`as`("the resolution table must exist at the current version").isTrue()
        assertThat(syncStateDbAdapter.count()).isZero()
    }

    @Test
    fun `a base snapshot round trips its payload and watermark`() {
        syncStateDbAdapter.putBase(
            SyncRecordType.TRANSACTION,
            "tx-1",
            payload = """{"description":"Lunch"}""",
            modifiedAt = 1_700_000_000_000L
        )

        val base = syncStateDbAdapter.getBase(SyncRecordType.TRANSACTION, "tx-1")
        assertThat(base).isNotNull()
        assertThat(base!!.recordKey).isEqualTo("tx-1")
        assertThat(base.payload).isEqualTo("""{"description":"Lunch"}""")
        assertThat(base.baseModifiedAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `a base snapshot for an unknown record is null rather than empty`() {
        assertThat(syncStateDbAdapter.getBase(SyncRecordType.ACCOUNT, "nope")).isNull()
    }

    @Test
    fun `re-basing the same record updates it in place`() {
        val first = syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "tx-1", "v1", 100L)
        val second = syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "tx-1", "v2", 200L)

        assertThat(syncStateDbAdapter.count()).isEqualTo(1L)
        assertThat(second.uid).isEqualTo(first.uid)
        assertThat(syncStateDbAdapter.getBase(SyncRecordType.TRANSACTION, "tx-1")!!.payload)
            .isEqualTo("v2")
    }

    @Test
    fun `the same key under two record types stays two distinct rows`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "same-key", "as-tx", 100L)
        syncStateDbAdapter.putBase(SyncRecordType.ACCOUNT, "same-key", "as-account", 100L)

        assertThat(syncStateDbAdapter.count()).isEqualTo(2L)
        assertThat(syncStateDbAdapter.getBase(SyncRecordType.ACCOUNT, "same-key")!!.payload)
            .isEqualTo("as-account")
    }

    @Test
    fun `bases can be filtered by record type`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "p", 1L)
        syncStateDbAdapter.putBase(SyncRecordType.SPLIT, "s1", "p", 2L)

        assertThat(syncStateDbAdapter.getBases(listOf(SyncRecordType.SPLIT)).map { it.recordKey })
            .containsExactly("s1")
        assertThat(syncStateDbAdapter.getBases()).hasSize(2)
    }

    @Test
    fun `removing a base snapshot targets one record only`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "p", 1L)
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t2", "p", 2L)

        assertThat(syncStateDbAdapter.removeBase(SyncRecordType.TRANSACTION, "t1")).isEqualTo(1)
        assertThat(syncStateDbAdapter.getBase(SyncRecordType.TRANSACTION, "t1")).isNull()
        assertThat(syncStateDbAdapter.getBase(SyncRecordType.TRANSACTION, "t2")).isNotNull()
    }

    // ------------------------------------------------------------- tombstones

    @Test
    fun `a tombstone can be written, found and cleared`() {
        assertThat(syncStateDbAdapter.isTombstoned(SyncRecordType.TRANSACTION, "tx-1")).isFalse()

        syncStateDbAdapter.addTombstone(
            SyncRecordType.TRANSACTION,
            "tx-1",
            deletedAt = 1_700_000_000_000L,
            deviceId = "device-a"
        )

        assertThat(syncStateDbAdapter.isTombstoned(SyncRecordType.TRANSACTION, "tx-1")).isTrue()
        val tombstone = syncStateDbAdapter.getTombstone(SyncRecordType.TRANSACTION, "tx-1")
        assertThat(tombstone).isNotNull()
        assertThat(tombstone!!.deletedAt).isEqualTo(1_700_000_000_000L)
        assertThat(tombstone.deviceId).isEqualTo("device-a")

        assertThat(syncStateDbAdapter.clearTombstone(SyncRecordType.TRANSACTION, "tx-1"))
            .isEqualTo(1)
        assertThat(syncStateDbAdapter.tombstoneCount()).isZero()
    }

    @Test
    fun `tombstoning the same record twice does not duplicate the marker`() {
        syncStateDbAdapter.addTombstone(SyncRecordType.TRANSACTION, "tx-1", 100L, "a")
        syncStateDbAdapter.addTombstone(SyncRecordType.TRANSACTION, "tx-1", 200L, "b")

        assertThat(syncStateDbAdapter.tombstoneCount()).isEqualTo(1L)
        assertThat(syncStateDbAdapter.getTombstone(SyncRecordType.TRANSACTION, "tx-1")!!.deletedAt)
            .isEqualTo(200L)
    }

    @Test
    fun `tombstones for the same key under different types stay separate`() {
        syncStateDbAdapter.addTombstone(SyncRecordType.TRANSACTION, "k", 1L)
        syncStateDbAdapter.addTombstone(SyncRecordType.SPLIT, "k", 1L)

        assertThat(syncStateDbAdapter.tombstoneCount()).isEqualTo(2L)
    }

    @Test
    fun `tombstones are listed oldest first`() {
        syncStateDbAdapter.addTombstone(SyncRecordType.TRANSACTION, "later", 200L)
        syncStateDbAdapter.addTombstone(SyncRecordType.TRANSACTION, "earlier", 100L)

        assertThat(syncStateDbAdapter.getTombstones().map { it.recordKey })
            .containsExactly("earlier", "later")
    }

    // --------------------------------------- snapshot + watermark consistency

    @Test
    fun `an empty snapshot has a zero watermark`() {
        val snapshot = syncStateDbAdapter.readSnapshot()
        assertThat(snapshot.records).isEmpty()
        assertThat(snapshot.watermark).isZero()
    }

    @Test
    fun `the watermark is the newest base timestamp of the returned rows`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "p", 100L)
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t2", "p", 300L)
        syncStateDbAdapter.putBase(SyncRecordType.SPLIT, "s1", "p", 200L)

        val snapshot = syncStateDbAdapter.readSnapshot()
        assertThat(snapshot.records).hasSize(3)
        assertThat(snapshot.watermark).isEqualTo(300L)
    }

    @Test
    fun `restricting the snapshot also restricts the watermark it describes`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "p", 100L)
        syncStateDbAdapter.putBase(SyncRecordType.SPLIT, "s1", "p", 900L)

        val snapshot = syncStateDbAdapter.readSnapshot(listOf(SyncRecordType.TRANSACTION))
        assertThat(snapshot.records.map { it.recordKey }).containsExactly("t1")
        assertThat(snapshot.watermark).isEqualTo(100L)
    }

    @Test
    fun `a write landing mid-read cannot make the snapshot describe rows it omits`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "p", 100L)
        val db = accountsDbAdapter.holder.db

        // The seam fires after the rows are materialised: this is exactly the window
        // a "read the rows, then query the max separately" implementation would fall
        // into. The watermark must still describe only the rows in the snapshot.
        val snapshot = syncStateDbAdapter.readSnapshot {
            db.execSQL(
                "INSERT INTO ${SyncStateEntry.TABLE_NAME} " +
                        "(${SyncStateEntry.COLUMN_UID}, ${SyncStateEntry.COLUMN_RECORD_TYPE}, " +
                        "${SyncStateEntry.COLUMN_RECORD_KEY}, ${SyncStateEntry.COLUMN_PAYLOAD}, " +
                        "${SyncStateEntry.COLUMN_BASE_MODIFIED_AT}) " +
                        "VALUES ('late-uid', 'transaction', 'late', 'p', 999999)"
            )
        }

        assertThat(snapshot.records.map { it.recordKey }).containsExactly("t1")
        assertThat(snapshot.watermark)
            .`as`("the watermark must describe the rows in this very snapshot")
            .isEqualTo(snapshot.records.maxOfOrNull { it.baseModifiedAt } ?: 0L)
        assertThat(snapshot.watermark).isEqualTo(100L)
    }

    @Test
    fun `the snapshot is self-consistent across repeated reads while rows are added`() {
        val db = accountsDbAdapter.holder.db
        repeat(20) { index ->
            if (index > 0) {
                syncStateDbAdapter.putBase(
                    SyncRecordType.TRANSACTION,
                    "t$index",
                    "p",
                    index.toLong()
                )
            }
            val snapshot = syncStateDbAdapter.readSnapshot()
            assertThat(snapshot.watermark)
                .isEqualTo(snapshot.records.maxOfOrNull { it.baseModifiedAt } ?: 0L)
        }
        assertThat(syncStateDbAdapter.count()).isEqualTo(19L)
        assertThat(db.isOpen).isTrue()
    }

    // ---------------------------------------------------------- etag and pending

    @Test
    fun `a base round trips its etag`() {
        syncStateDbAdapter.putBase(
            SyncRecordType.TRANSACTION, "t1", "a=1", 5L, etag = "abc123"
        )
        assertThat(syncStateDbAdapter.getBase(SyncRecordType.TRANSACTION, "t1")?.etag)
            .isEqualTo("abc123")
    }

    @Test
    fun `re-basing without an etag clears it rather than leaving a stale one`() {
        // The executor records whatever the listing actually reported. Keeping a stale etag
        // here would make the next round skip a fetch it should have made.
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "a=1", 5L, etag = "abc")
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "a=1", 5L, etag = null)

        assertThat(syncStateDbAdapter.getBase(SyncRecordType.TRANSACTION, "t1")?.etag).isNull()
    }

    @Test
    fun `the pending marker can be set, read and cleared without touching the payload`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "a=1", 5L, etag = "e1")

        assertThat(syncStateDbAdapter.setPendingSince(SyncRecordType.TRANSACTION, "t1", 100L))
            .isEqualTo(1)
        val watching = syncStateDbAdapter.getPendingBases()
        assertThat(watching).hasSize(1)
        assertThat(watching.first().pendingSince).isEqualTo(100L)
        // The payload and etag must survive the marker write — it is a single-column update.
        assertThat(watching.first().payload).isEqualTo("a=1")
        assertThat(watching.first().etag).isEqualTo("e1")

        syncStateDbAdapter.setPendingSince(SyncRecordType.TRANSACTION, "t1", null)
        assertThat(syncStateDbAdapter.getPendingBases()).isEmpty()
    }

    @Test
    fun `re-basing a record keeps a pending marker it is still subject to`() {
        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "a=1", 5L, etag = "e1")
        syncStateDbAdapter.setPendingSince(SyncRecordType.TRANSACTION, "t1", 100L)

        syncStateDbAdapter.putBase(SyncRecordType.TRANSACTION, "t1", "a=2", 6L, etag = "e2")

        val base = syncStateDbAdapter.getBase(SyncRecordType.TRANSACTION, "t1")
        assertThat(base?.pendingSince).isEqualTo(100L)
        assertThat(base?.payload).isEqualTo("a=2")
    }

    @Test
    fun `a pending marker on an unknown record updates nothing`() {
        assertThat(syncStateDbAdapter.setPendingSince(SyncRecordType.TRANSACTION, "nope", 1L))
            .isZero()
    }

    // ------------------------------------------------------------ resolutions

    @Test
    fun `a stored answer round trips`() {
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL)

        assertThat(syncStateDbAdapter.getResolution(SyncRecordType.TRANSACTION, "t1"))
            .isEqualTo(ResolveSide.LOCAL)
        assertThat(syncStateDbAdapter.getResolutions())
            .containsEntry(SyncIdentity(SyncRecordType.TRANSACTION, "t1"), ResolveSide.LOCAL)
    }

    @Test
    fun `choosing the other side replaces the answer instead of piling up`() {
        // Two contradicting rows for one record would make which one wins depend on `_id`,
        // i.e. on insert order — a coin flip nobody could see.
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL)
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.REMOTE)

        assertThat(syncStateDbAdapter.resolutionCount()).isEqualTo(1L)
        assertThat(syncStateDbAdapter.getResolution(SyncRecordType.TRANSACTION, "t1"))
            .isEqualTo(ResolveSide.REMOTE)
    }

    @Test
    fun `putting an answer reports whether the row landed`() {
        // SQLite reports a failed insert by returning `-1` rather than by throwing, so the caller
        // needs this answer — otherwise a button that stored nothing would report success.
        assertThat(syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL))
            .isTrue()
        assertThat(syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", null))
            .`as`("clearing leaves no answer stored, which is the post-condition either way")
            .isTrue()
    }

    @Test
    fun `an answer can exist for a record that has no base row`() {
        // "Created on both devices with different content" is a conflict with no common
        // ancestor — the most common kind — so the override must not depend on a base row.
        assertThat(syncStateDbAdapter.getBase(SyncRecordType.ACCOUNT, "a1")).isNull()

        syncStateDbAdapter.putResolution(SyncRecordType.ACCOUNT, "a1", ResolveSide.REMOTE)

        assertThat(syncStateDbAdapter.getResolution(SyncRecordType.ACCOUNT, "a1"))
            .isEqualTo(ResolveSide.REMOTE)
    }

    @Test
    fun `a stored answer is scoped to its record type`() {
        // A transaction and an account may share a GUID; one's answer must not answer for both.
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "shared", ResolveSide.LOCAL)

        assertThat(syncStateDbAdapter.getResolution(SyncRecordType.ACCOUNT, "shared")).isNull()
    }

    @Test
    fun `putting a null side clears the answer`() {
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL)

        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", null)

        assertThat(syncStateDbAdapter.getResolution(SyncRecordType.TRANSACTION, "t1")).isNull()
        assertThat(syncStateDbAdapter.resolutionCount()).isZero()
    }

    @Test
    fun `an answer this build cannot read is ignored, not guessed at`() {
        // A row written by a newer build, or a corrupt one. Answering with a default would
        // silently force a side the user never chose — the exact failure this feature is about.
        val db = accountsDbAdapter.holder.db
        db.execSQL(
            "INSERT INTO ${SyncResolutionEntry.TABLE_NAME} " +
                "(${SyncResolutionEntry.COLUMN_RECORD_TYPE}, ${SyncResolutionEntry.COLUMN_RECORD_KEY}, " +
                "${SyncResolutionEntry.COLUMN_SIDE}) VALUES ('transaction', 't1', 'sideways')"
        )

        assertThat(syncStateDbAdapter.getResolutions()).isEmpty()
        assertThat(syncStateDbAdapter.getResolution(SyncRecordType.TRANSACTION, "t1")).isNull()

        // An unknown record type is skipped the same way.
        db.execSQL(
            "INSERT INTO ${SyncResolutionEntry.TABLE_NAME} " +
                "(${SyncResolutionEntry.COLUMN_RECORD_TYPE}, ${SyncResolutionEntry.COLUMN_RECORD_KEY}, " +
                "${SyncResolutionEntry.COLUMN_SIDE}) VALUES ('budget', 'b1', 'local')"
        )

        assertThat(syncStateDbAdapter.getResolutions()).isEmpty()
        assertThat(syncStateDbAdapter.resolutionCount())
            .`as`("the rows are there — they were skipped, not missing")
            .isEqualTo(2L)
    }

    @Test
    fun `clearing an answer that is not there removes nothing`() {
        assertThat(syncStateDbAdapter.clearResolution(SyncRecordType.TRANSACTION, "nope")).isZero()
    }

    @Test
    fun `clearing a batch removes exactly the records it names`() {
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL)
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t2", ResolveSide.REMOTE)
        syncStateDbAdapter.putResolution(SyncRecordType.ACCOUNT, "a1", ResolveSide.LOCAL)

        val removed = syncStateDbAdapter.clearResolutions(
            listOf(
                SyncIdentity(SyncRecordType.TRANSACTION, "t1"),
                SyncIdentity(SyncRecordType.TRANSACTION, "t2")
            )
        )

        assertThat(removed).isEqualTo(2)
        assertThat(syncStateDbAdapter.getResolutions())
            .containsOnlyKeys(SyncIdentity(SyncRecordType.ACCOUNT, "a1"))
    }

    @Test
    fun `clearing an empty batch touches nothing`() {
        syncStateDbAdapter.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL)

        assertThat(syncStateDbAdapter.clearResolutions(emptyList())).isZero()
        assertThat(syncStateDbAdapter.resolutionCount()).isEqualTo(1L)
    }
}

/**
 * Migration contract for the sync tables (feature ② v2): the version-33 tables are
 * purely additive, safe against an older database and safe to re-run; version 34 adds
 * two columns to `sync_state` and must back-fill them on an existing 33 database.
 * Version 35 adds the local-only `transaction_templates` table (feature ①d); version 36
 * adds the conflict-resolution override table (feature ② v3).
 */
class SyncStateMigrationTest : GnuCashTest() {

    @Test
    fun `database version is 36`() {
        assertThat(DatabaseSchema.DATABASE_VERSION).isEqualTo(36)
    }

    @Test
    fun `migrate to 33 creates the sync tables and is idempotent`() {
        val file = File(context.cacheDir, "sync_state_migration_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_RECORD_KEY))
                .`as`("sync_state must not exist before the migration")
                .isFalse()
            assertThat(db.hasTableColumn(TombstoneEntry.TABLE_NAME, TombstoneEntry.COLUMN_RECORD_KEY))
                .`as`("tombstones must not exist before the migration")
                .isFalse()

            MigrationHelper.migrate(context, db, 32, 33)

            assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_RECORD_TYPE)).isTrue()
            assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_RECORD_KEY)).isTrue()
            assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_PAYLOAD)).isTrue()
            assertThat(
                db.hasTableColumn(
                    SyncStateEntry.TABLE_NAME,
                    SyncStateEntry.COLUMN_BASE_MODIFIED_AT
                )
            ).isTrue()
            assertThat(db.hasTableColumn(TombstoneEntry.TABLE_NAME, TombstoneEntry.COLUMN_RECORD_TYPE)).isTrue()
            assertThat(db.hasTableColumn(TombstoneEntry.TABLE_NAME, TombstoneEntry.COLUMN_DELETED_AT)).isTrue()
            assertThat(db.hasTableColumn(TombstoneEntry.TABLE_NAME, TombstoneEntry.COLUMN_DEVICE_ID)).isTrue()
            assertThat(indexExists(db, SyncStateEntry.INDEX_UNIQUE)).isTrue()
            assertThat(indexExists(db, TombstoneEntry.INDEX_UNIQUE)).isTrue()

            // Re-running must be a no-op, not an error (IF NOT EXISTS + dropped triggers).
            MigrationHelper.migrate(context, db, 32, 33)

            db.execSQL(
                "INSERT INTO ${SyncStateEntry.TABLE_NAME} " +
                        "(${SyncStateEntry.COLUMN_UID}, ${SyncStateEntry.COLUMN_RECORD_TYPE}, " +
                        "${SyncStateEntry.COLUMN_RECORD_KEY}, ${SyncStateEntry.COLUMN_PAYLOAD}, " +
                        "${SyncStateEntry.COLUMN_BASE_MODIFIED_AT}) " +
                        "VALUES ('uid-1', 'transaction', 'tx-1', 'p', 5)"
            )
            // One base per (type, key) — the unique index must reject a duplicate.
            var rejected = false
            try {
                db.execSQL(
                    "INSERT INTO ${SyncStateEntry.TABLE_NAME} " +
                            "(${SyncStateEntry.COLUMN_UID}, ${SyncStateEntry.COLUMN_RECORD_TYPE}, " +
                            "${SyncStateEntry.COLUMN_RECORD_KEY}, ${SyncStateEntry.COLUMN_PAYLOAD}, " +
                            "${SyncStateEntry.COLUMN_BASE_MODIFIED_AT}) " +
                            "VALUES ('uid-2', 'transaction', 'tx-1', 'p', 6)"
                )
            } catch (expected: android.database.SQLException) {
                rejected = true
            }
            assertThat(rejected).`as`("a record can only have one base snapshot").isTrue()
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun `migrate to 34 back-fills the etag and pending columns on an existing 33 table`() {
        val file = File(context.cacheDir, "sync_state_migration_34_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            // The version-33 shape, verbatim: sync_state without base_etag / pending_since.
            db.execSQL(
                "CREATE TABLE sync_state (" +
                    "id integer primary key autoincrement, " +
                    "uid varchar(255) not null UNIQUE, " +
                    "record_type varchar(64) not null, " +
                    "record_key varchar(255) not null, " +
                    "payload text, " +
                    "base_modified_at integer not null default 0, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);"
            )
            db.execSQL(
                "INSERT INTO sync_state (uid, record_type, record_key, payload, base_modified_at) " +
                    "VALUES ('u1','transaction','t1','a=1',7)"
            )
            assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_BASE_ETAG))
                .`as`("the column must not exist before the migration")
                .isFalse()

            MigrationHelper.migrate(context, db, 33, 34)
            // Re-running must be a no-op: ADD COLUMN has no IF NOT EXISTS, so this is the
            // behaviour that would break first if the guard were dropped.
            MigrationHelper.migrate(context, db, 33, 34)

            assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_BASE_ETAG)).isTrue()
            assertThat(db.hasTableColumn(SyncStateEntry.TABLE_NAME, SyncStateEntry.COLUMN_PENDING_SINCE)).isTrue()

            db.rawQuery(
                "SELECT payload, base_etag, pending_since FROM sync_state WHERE record_key = 't1'", null
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("a=1")
                assertThat(cursor.isNull(1)).isTrue()
                assertThat(cursor.isNull(2)).isTrue()
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun `migrate to 36 creates the resolution table and is idempotent`() {
        val file = File(context.cacheDir, "sync_resolution_migration_36_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            assertThat(
                db.hasTableColumn(SyncResolutionEntry.TABLE_NAME, SyncResolutionEntry.COLUMN_SIDE)
            ).`as`("the table must not exist before the migration").isFalse()

            MigrationHelper.migrate(context, db, 35, 36)
            // Re-running must be a no-op, not an error (CREATE TABLE / INDEX IF NOT EXISTS).
            MigrationHelper.migrate(context, db, 35, 36)

            assertThat(db.hasTableColumn(SyncResolutionEntry.TABLE_NAME, SyncResolutionEntry.COLUMN_RECORD_TYPE)).isTrue()
            assertThat(db.hasTableColumn(SyncResolutionEntry.TABLE_NAME, SyncResolutionEntry.COLUMN_RECORD_KEY)).isTrue()
            assertThat(db.hasTableColumn(SyncResolutionEntry.TABLE_NAME, SyncResolutionEntry.COLUMN_SIDE)).isTrue()
            assertThat(indexExists(db, SyncResolutionEntry.INDEX_UNIQUE)).isTrue()

            // One answer per record: the unique index has to reject a second row for the same key,
            // or "keep local" and "keep remote" could both be stored and which one wins would
            // depend on `_id`.
            db.execSQL(
                "INSERT INTO ${SyncResolutionEntry.TABLE_NAME} " +
                    "(${SyncResolutionEntry.COLUMN_RECORD_TYPE}, ${SyncResolutionEntry.COLUMN_RECORD_KEY}, " +
                    "${SyncResolutionEntry.COLUMN_SIDE}) VALUES ('transaction', 't1', 'local')"
            )
            var rejected = false
            try {
                db.execSQL(
                    "INSERT INTO ${SyncResolutionEntry.TABLE_NAME} " +
                        "(${SyncResolutionEntry.COLUMN_RECORD_TYPE}, ${SyncResolutionEntry.COLUMN_RECORD_KEY}, " +
                        "${SyncResolutionEntry.COLUMN_SIDE}) VALUES ('transaction', 't1', 'remote')"
                )
            } catch (expected: android.database.SQLException) {
                rejected = true
            }
            assertThat(rejected).`as`("a record can only carry one answer").isTrue()
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun `migrating an older database all the way up creates the resolution table too`() {
        val file = File(context.cacheDir, "sync_resolution_full_migration_test.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            // The migration is chained, not only reachable from 35.
            MigrationHelper.migrate(context, db, 30, DatabaseSchema.DATABASE_VERSION)
            assertThat(
                db.hasTableColumn(SyncResolutionEntry.TABLE_NAME, SyncResolutionEntry.COLUMN_SIDE)
            ).`as`("the resolution table must be present after a full chain").isTrue()
        } finally {
            db.close()
            file.delete()
        }
    }

    private fun indexExists(db: SQLiteDatabase, name: String): Boolean {        var found = false
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
        ).use { cursor ->
            found = cursor.moveToFirst()
        }
        return found
    }
}
