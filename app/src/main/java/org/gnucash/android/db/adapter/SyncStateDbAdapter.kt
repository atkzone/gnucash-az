/*
 * Copyright (c) 2026 GnuCash Pocket contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.db.adapter

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.annotation.VisibleForTesting
import org.gnucash.android.db.DatabaseHolder
import org.gnucash.android.db.DatabaseSchema.SyncResolutionEntry
import org.gnucash.android.db.DatabaseSchema.SyncStateEntry
import org.gnucash.android.db.DatabaseSchema.TombstoneEntry
import org.gnucash.android.model.SyncBase
import org.gnucash.android.model.Tombstone
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import java.io.IOException

/**
 * Persistence for the merge machinery (feature ② v2).
 *
 * Owns **both** sync tables, mirroring how [TagsDbAdapter] owns the two tag
 * tables: `sync_state` is the per-record base snapshot and `tombstones` is the
 * deletion evidence. Keeping them behind one adapter is what makes
 * "adopt a change and re-base it" a single, reviewable step.
 *
 * Two plan constraints from §7.3 are encoded here rather than left to callers:
 *
 * 1. **Snapshot and watermark are read together.** [readSnapshot] materialises the
 *    rows and derives the watermark **from those very rows**, inside one
 *    transaction. Splitting it into "read the rows, then query the max" is how a
 *    snapshot ends up describing itself inconsistently and destroys data downstream.
 * 2. **Deletions are recorded, never inferred.** [addTombstone] only accepts an
 *    already-confirmed key (see [org.gnucash.android.sync.ThreeWayMerge.planDeletions]);
 *    there is deliberately no "delete the rows I didn't see" helper here.
 */
class SyncStateDbAdapter(
    holder: DatabaseHolder
) : DatabaseAdapter<SyncBase>(
    holder,
    SyncStateEntry.TABLE_NAME,
    entryColumns
) {
    override fun buildModelInstance(cursor: Cursor): SyncBase {
        val type = SyncRecordType.fromId(cursor.getString(INDEX_COLUMN_RECORD_TYPE))
            ?: SyncRecordType.TRANSACTION
        val base = SyncBase(
            recordType = type,
            recordKey = cursor.getString(INDEX_COLUMN_RECORD_KEY) ?: "",
            payload = cursor.getString(INDEX_COLUMN_PAYLOAD) ?: "",
            baseModifiedAt = cursor.getLong(INDEX_COLUMN_BASE_MODIFIED_AT),
            etag = if (cursor.isNull(INDEX_COLUMN_BASE_ETAG)) {
                null
            } else {
                cursor.getString(INDEX_COLUMN_BASE_ETAG)
            },
            pendingSince = if (cursor.isNull(INDEX_COLUMN_PENDING_SINCE)) {
                null
            } else {
                cursor.getLong(INDEX_COLUMN_PENDING_SINCE)
            }
        )
        populateBaseModelAttributes(cursor, base)
        return base
    }

    override fun bind(stmt: SQLiteStatement, base: SyncBase): SQLiteStatement {
        bindBaseModel(stmt, base)
        stmt.bindString(1 + INDEX_COLUMN_RECORD_TYPE, base.recordType.id)
        stmt.bindString(1 + INDEX_COLUMN_RECORD_KEY, base.recordKey)
        stmt.bindString(1 + INDEX_COLUMN_PAYLOAD, base.payload)
        stmt.bindLong(1 + INDEX_COLUMN_BASE_MODIFIED_AT, base.baseModifiedAt)
        val etag = base.etag
        if (etag != null) {
            stmt.bindString(1 + INDEX_COLUMN_BASE_ETAG, etag)
        } else {
            stmt.bindNull(1 + INDEX_COLUMN_BASE_ETAG)
        }
        val pendingSince = base.pendingSince
        if (pendingSince != null) {
            stmt.bindLong(1 + INDEX_COLUMN_PENDING_SINCE, pendingSince)
        } else {
            stmt.bindNull(1 + INDEX_COLUMN_PENDING_SINCE)
        }
        return stmt
    }

    // ---------------------------------------------------------------- base rows

    /** The base snapshot for one record, or `null` when it has never been synced. */
    fun getBase(recordType: SyncRecordType, recordKey: String): SyncBase? =
        getAllRecords(
            "${SyncStateEntry.COLUMN_RECORD_TYPE} = ? AND ${SyncStateEntry.COLUMN_RECORD_KEY} = ?",
            arrayOf(recordType.id, recordKey)
        ).firstOrNull()

    /**
     * Writes (or replaces) the base snapshot for one record.
     *
     * The existing row is looked up first and its `uid` **and** `pendingSince` reused,
     * so a re-base is an in-place update of the `(record_type, record_key)` row rather
     * than a second row the unique index would reject — and so that re-basing a record
     * (which happens on every adopt, upload or agreement) does not silently drop a
     * watch marker it is still subject to. Clearing the marker is an explicit
     * [setPendingSince] call, never a side effect of re-basing.
     *
     * @param etag the peer's entity tag now believed to match [payload]; `null` when the
     *             server reported none, which forces the next round to fetch rather than
     *             assume the content is unchanged.
     */
    fun putBase(
        recordType: SyncRecordType,
        recordKey: String,
        payload: String,
        modifiedAt: Long,
        etag: String? = null
    ): SyncBase {
        val existing = getBase(recordType, recordKey)
        val base = SyncBase(
            recordType = recordType,
            recordKey = recordKey,
            payload = payload,
            baseModifiedAt = modifiedAt,
            etag = etag,
            pendingSince = existing?.pendingSince
        )
        existing?.let { base.setUID(it.uid) }
        addRecord(base, UpdateMethod.Replace)
        return base
    }

    /**
     * Sets (or clears) the "first seen absent" marker of one record.
     *
     * An `UPDATE` of the single column, deliberately not a re-base: the round's
     * pending bookkeeping must never touch the payload or etag it is tracking.
     *
     * @param since epoch millis, or `null` to stop watching the record (it came back).
     * @return the number of rows updated; `0` when the record has no base row.
     */
    fun setPendingSince(recordType: SyncRecordType, recordKey: String, since: Long?): Int {
        val values = ContentValues().apply {
            if (since == null) {
                putNull(SyncStateEntry.COLUMN_PENDING_SINCE)
            } else {
                put(SyncStateEntry.COLUMN_PENDING_SINCE, since)
            }
        }
        return db.update(
            SyncStateEntry.TABLE_NAME,
            values,
            "${SyncStateEntry.COLUMN_RECORD_TYPE} = ? AND ${SyncStateEntry.COLUMN_RECORD_KEY} = ?",
            arrayOf(recordType.id, recordKey)
        )
    }

    /** The base snapshots currently being watched for a possible deletion. */
    fun getPendingBases(): List<SyncBase> = getBases().filter { it.pendingSince != null }

    /** Every base snapshot, newest first (by the record's own `modified_at`). */
    fun getBases(): List<SyncBase> =
        getAllRecords(null, null, "${SyncStateEntry.COLUMN_BASE_MODIFIED_AT} DESC")

    /** Base snapshots of the given [types]. */
    fun getBases(types: Collection<SyncRecordType>): List<SyncBase> =
        getBases().filter { it.recordType in types }

    fun removeBase(recordType: SyncRecordType, recordKey: String): Int =
        db.delete(
            SyncStateEntry.TABLE_NAME,
            "${SyncStateEntry.COLUMN_RECORD_TYPE} = ? AND ${SyncStateEntry.COLUMN_RECORD_KEY} = ?",
            arrayOf(recordType.id, recordKey)
        )

    fun count(): Long = getRecordsCount(null, null)

    // -------------------------------------------------------------- resolutions

    /**
     * Every stored conflict-resolution override (feature ② v3), keyed by record identity.
     *
     * A row whose `record_type` or `side` this build cannot read is **skipped**, never
     * guessed: an override we cannot interpret must not be turned into a forced write, and
     * the honest failure of "the user's answer is silently ignored" is still better than
     * "the round picked a side the user never chose". (A row from a *newer* build is the
     * only way to get one; both columns are constrained by this build's writers.)
     *
     * Ordered by `(record_type, record_key)` so a log or a test sees a stable list rather
     * than whatever the index happens to hand back.
     */
    fun getResolutions(): Map<SyncIdentity, ResolveSide> {
        val out = LinkedHashMap<SyncIdentity, ResolveSide>()
        db.query(
            SyncResolutionEntry.TABLE_NAME,
            RESOLUTION_COLUMNS,
            null,
            null,
            null,
            null,
            "${SyncResolutionEntry.COLUMN_RECORD_TYPE} ASC, ${SyncResolutionEntry.COLUMN_RECORD_KEY} ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = SyncRecordType.fromId(cursor.getString(INDEX_RESOLUTION_TYPE)) ?: continue
                val side = ResolveSide.fromId(cursor.getString(INDEX_RESOLUTION_SIDE)) ?: continue
                out[SyncIdentity(type, cursor.getString(INDEX_RESOLUTION_KEY) ?: "")] = side
            }
        }
        return out
    }

    /** The override stored for one record, or `null` when it has none. */
    fun getResolution(recordType: SyncRecordType, recordKey: String): ResolveSide? {
        var side: ResolveSide? = null
        db.query(
            SyncResolutionEntry.TABLE_NAME,
            arrayOf(SyncResolutionEntry.COLUMN_SIDE),
            "${SyncResolutionEntry.COLUMN_RECORD_TYPE} = ? AND ${SyncResolutionEntry.COLUMN_RECORD_KEY} = ?",
            arrayOf(recordType.id, recordKey),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) side = ResolveSide.fromId(cursor.getString(0))
        }
        return side
    }

    /**
     * Stores (or replaces) the user's answer for one record.
     *
     * A **replace**, not an append: the unique index is on `(record_type, record_key)`, so
     * choosing "keep local" after having chosen "keep the peer's" leaves exactly one row.
     * Two contradicting overrides for the same record would make which one wins depend on
     * `_id`, i.e. on insert order — a coin flip nobody could see.
     *
     * @param side the side to keep, or `null` to clear any stored answer.
     * @return `true` when the row landed (or, for a `null` side, when no answer is stored).
     *         `false` means the write **did not happen** — `insertWithOnConflict` reports that
     *         by returning `-1` rather than by throwing, so a caller that ignored this would
     *         report a button that silently did nothing.
     */
    fun putResolution(recordType: SyncRecordType, recordKey: String, side: ResolveSide?): Boolean {
        if (side == null) {
            clearResolution(recordType, recordKey)
            return true
        }
        val values = ContentValues().apply {
            put(SyncResolutionEntry.COLUMN_RECORD_TYPE, recordType.id)
            put(SyncResolutionEntry.COLUMN_RECORD_KEY, recordKey)
            put(SyncResolutionEntry.COLUMN_SIDE, side.id)
        }
        val row = db.insertWithOnConflict(
            SyncResolutionEntry.TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return row != -1L
    }

    /** Clears the override for one record; `0` when there was none. */
    fun clearResolution(recordType: SyncRecordType, recordKey: String): Int =
        db.delete(
            SyncResolutionEntry.TABLE_NAME,
            "${SyncResolutionEntry.COLUMN_RECORD_TYPE} = ? AND ${SyncResolutionEntry.COLUMN_RECORD_KEY} = ?",
            arrayOf(recordType.id, recordKey)
        )

    /**
     * Clears several overrides in one transaction (feature ② v3).
     *
     * The round clears a whole batch at once (applied-and-landed, plus moot), and doing it
     * row-by-row outside a transaction would leave a crash mid-way with some answers consumed
     * and some not — indistinguishable, on the next round, from "the user answered these and
     * not those". All-or-nothing is the only shape whose interruption is honest.
     *
     * @return how many rows were removed (rows that were already gone are not counted).
     */
    fun clearResolutions(identities: Collection<SyncIdentity>): Int {
        if (identities.isEmpty()) return 0
        var removed = 0
        db.beginTransactionNonExclusive()
        try {
            for (identity in identities) {
                removed += clearResolution(identity.recordType, identity.key)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return removed
    }

    /** How many overrides are stored; for tests and diagnostics. */
    fun resolutionCount(): Long {
        var count = 0L
        db.rawQuery("SELECT COUNT(*) FROM ${SyncResolutionEntry.TABLE_NAME}", null).use { cursor ->
            if (cursor.moveToFirst()) count = cursor.getLong(0)
        }
        return count
    }

    // -------------------------------------------------------------- tombstones

    /**
     * Records a confirmed deletion. Idempotent: re-tombstoning the same record
     * updates the marker instead of adding a second one.
     */
    fun addTombstone(
        recordType: SyncRecordType,
        recordKey: String,
        deletedAt: Long,
        deviceId: String? = null
    ): Tombstone {
        val existing = getTombstone(recordType, recordKey)
        val tombstone = Tombstone(recordType, recordKey, deletedAt, deviceId)
        existing?.let { tombstone.setUID(it.uid) }
        val values = ContentValues().apply {
            put(TombstoneEntry.COLUMN_UID, tombstone.uid)
            put(TombstoneEntry.COLUMN_RECORD_TYPE, tombstone.recordType.id)
            put(TombstoneEntry.COLUMN_RECORD_KEY, tombstone.recordKey)
            put(TombstoneEntry.COLUMN_DELETED_AT, tombstone.deletedAt)
            put(TombstoneEntry.COLUMN_CREATED_AT, tombstone.createdTimestamp.time)
            put(TombstoneEntry.COLUMN_MODIFIED_AT, tombstone.modifiedTimestamp.time)
            put(TombstoneEntry.COLUMN_DEVICE_ID, tombstone.deviceId)
        }
        db.insertWithOnConflict(
            TombstoneEntry.TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return tombstone
    }

    fun getTombstone(recordType: SyncRecordType, recordKey: String): Tombstone? {
        var result: Tombstone? = null
        db.query(
            TombstoneEntry.TABLE_NAME,
            TOMBSTONE_COLUMNS,
            "${TombstoneEntry.COLUMN_RECORD_TYPE} = ? AND ${TombstoneEntry.COLUMN_RECORD_KEY} = ?",
            arrayOf(recordType.id, recordKey),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) result = readTombstone(cursor)
        }
        return result
    }

    fun getTombstones(): List<Tombstone> {
        val out = mutableListOf<Tombstone>()
        db.query(
            TombstoneEntry.TABLE_NAME,
            TOMBSTONE_COLUMNS,
            null,
            null,
            null,
            null,
            "${TombstoneEntry.COLUMN_DELETED_AT} ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(readTombstone(cursor))
        }
        return out
    }

    fun isTombstoned(recordType: SyncRecordType, recordKey: String): Boolean =
        getTombstone(recordType, recordKey) != null

    fun clearTombstone(recordType: SyncRecordType, recordKey: String): Int =
        db.delete(
            TombstoneEntry.TABLE_NAME,
            "${TombstoneEntry.COLUMN_RECORD_TYPE} = ? AND ${TombstoneEntry.COLUMN_RECORD_KEY} = ?",
            arrayOf(recordType.id, recordKey)
        )

    fun tombstoneCount(): Long {
        var count = 0L
        db.rawQuery("SELECT COUNT(*) FROM ${TombstoneEntry.TABLE_NAME}", null).use { cursor ->
            if (cursor.moveToFirst()) count = cursor.getLong(0)
        }
        return count
    }

    // ---------------------------------------------------------------- snapshot

    /**
     * Reads a full base snapshot **and its watermark** in one transaction.
     *
     * The watermark is the newest `base_modified_at` among the rows returned — it
     * is computed from the materialised list rather than queried separately. That
     * is what makes the pair self-consistent by construction: there is no window in
     * which a concurrent write could land between "rows read" and "watermark read"
     * and produce a snapshot whose watermark describes rows it does not contain
     * (plan §7.3, constraint 1; risk R-1).
     *
     * @param types restrict the snapshot to these record types.
     * @param onRowsRead test-only seam invoked after the rows are materialised and
     * before the watermark is derived. Production always leaves it `null`; a test
     * passes a write to prove the watermark really does describe the rows returned
     * (a two-query implementation would fail that assertion).
     */
    fun readSnapshot(
        types: Collection<SyncRecordType> = SyncRecordType.entries,
        @VisibleForTesting onRowsRead: (() -> Unit)? = null
    ): SyncSnapshot {
        db.beginTransactionNonExclusive()
        try {
            val records = getBases(types)
            onRowsRead?.invoke()
            val watermark = records.maxOfOrNull { it.baseModifiedAt } ?: 0L
            db.setTransactionSuccessful()
            return SyncSnapshot(records, watermark)
        } finally {
            db.endTransaction()
        }
    }

    @Throws(IOException::class)
    override fun close() {
        super.close()
    }

    private fun readTombstone(cursor: Cursor): Tombstone {
        val type = SyncRecordType.fromId(cursor.getString(1)) ?: SyncRecordType.TRANSACTION
        val tombstone = Tombstone(
            recordType = type,
            recordKey = cursor.getString(2) ?: "",
            deletedAt = cursor.getLong(3),
            deviceId = cursor.getString(4)
        )
        tombstone.id = cursor.getLong(0)
        return tombstone
    }

    companion object {
        private val entryColumns = arrayOf(
            SyncStateEntry.COLUMN_RECORD_TYPE,
            SyncStateEntry.COLUMN_RECORD_KEY,
            SyncStateEntry.COLUMN_PAYLOAD,
            SyncStateEntry.COLUMN_BASE_MODIFIED_AT,
            SyncStateEntry.COLUMN_BASE_ETAG,
            SyncStateEntry.COLUMN_PENDING_SINCE
        )
        private const val INDEX_COLUMN_RECORD_TYPE = 0
        private const val INDEX_COLUMN_RECORD_KEY = INDEX_COLUMN_RECORD_TYPE + 1
        private const val INDEX_COLUMN_PAYLOAD = INDEX_COLUMN_RECORD_KEY + 1
        private const val INDEX_COLUMN_BASE_MODIFIED_AT = INDEX_COLUMN_PAYLOAD + 1
        private const val INDEX_COLUMN_BASE_ETAG = INDEX_COLUMN_BASE_MODIFIED_AT + 1
        private const val INDEX_COLUMN_PENDING_SINCE = INDEX_COLUMN_BASE_ETAG + 1

        private val TOMBSTONE_COLUMNS = arrayOf(
            TombstoneEntry.COLUMN_ID,
            TombstoneEntry.COLUMN_RECORD_TYPE,
            TombstoneEntry.COLUMN_RECORD_KEY,
            TombstoneEntry.COLUMN_DELETED_AT,
            TombstoneEntry.COLUMN_DEVICE_ID
        )

        /** Columns of one resolution row, in the order the indices below assume. */
        private val RESOLUTION_COLUMNS = arrayOf(
            SyncResolutionEntry.COLUMN_RECORD_TYPE,
            SyncResolutionEntry.COLUMN_RECORD_KEY,
            SyncResolutionEntry.COLUMN_SIDE
        )
        private const val INDEX_RESOLUTION_TYPE = 0
        private const val INDEX_RESOLUTION_KEY = INDEX_RESOLUTION_TYPE + 1
        private const val INDEX_RESOLUTION_SIDE = INDEX_RESOLUTION_KEY + 1

        /**
         * Adapter backed by the currently active book, re-derived on every access
         * so a book switch is picked up automatically.
         */
        val instance: SyncStateDbAdapter get() = SyncStateDbAdapter(AccountsDbAdapter.instance.holder)
    }
}

/**
 * A set of base snapshots together with the watermark they describe.
 *
 * Both fields must always travel together — see [SyncStateDbAdapter.readSnapshot].
 */
data class SyncSnapshot(
    val records: List<SyncBase>,
    val watermark: Long
)
