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
import org.gnucash.android.db.DatabaseHolder
import org.gnucash.android.db.DatabaseSchema.TagEntry
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.db.DatabaseSchema.TransactionTagEntry
import org.gnucash.android.db.bindStringOrNull
import org.gnucash.android.model.Tag
import org.gnucash.android.model.Transaction
import org.gnucash.android.util.TagCodec
import timber.log.Timber
import java.io.IOException
import java.util.Locale

/**
 * Persistence for tags (feature ⑧).
 *
 * Owns **both** tag tables, mirroring how [BudgetsDbAdapter] owns `budgets` +
 * `budget_amounts`: `tags` is the registry (identity = name, `COLLATE NOCASE`)
 * and `transaction_tags` is the relation. Keeping them behind one adapter is what
 * makes rename/delete atomic across the two.
 *
 * The relation stores the tag **name** in [TransactionTagEntry.COLUMN_TAG]
 * because that is exactly what the search layer matches on; [TransactionTagEntry.COLUMN_TAG_UID]
 * links back to the registry so a rename can be propagated and a delete cascades.
 */
class TagsDbAdapter(
    holder: DatabaseHolder
) : DatabaseAdapter<Tag>(
    holder,
    TagEntry.TABLE_NAME,
    entryColumns
) {
    override fun buildModelInstance(cursor: Cursor): Tag {
        val name = cursor.getString(INDEX_COLUMN_NAME) ?: ""
        val color = cursor.getString(INDEX_COLUMN_COLOR)
        val tag = Tag(name)
        tag.colorCode = color
        populateBaseModelAttributes(cursor, tag)
        return tag
    }

    override fun bind(stmt: SQLiteStatement, tag: Tag): SQLiteStatement {
        bindBaseModel(stmt, tag)
        stmt.bindString(1 + INDEX_COLUMN_NAME, tag.name)
        stmt.bindStringOrNull(1 + INDEX_COLUMN_COLOR, tag.colorCode)
        return stmt
    }

    /** Looks a tag up by name; case-insensitive thanks to `COLLATE NOCASE`. */
    fun getRecordByName(name: String): Tag? {
        val normalized = TagCodec.normalizeName(name) ?: return null
        return getAllRecords("${TagEntry.COLUMN_NAME} = ?", arrayOf(normalized)).firstOrNull()
    }

    /** All tags in the registry, ordered by name. */
    fun getAll(): List<Tag> =
        getAllRecords(null, null, "${TagEntry.COLUMN_NAME} ASC")

    fun getTagNames(): List<String> = getAll().map { it.name }

    fun count(): Long = getRecordsCount(null, null)

    /**
     * Returns the tag for [name], creating it when it does not exist yet.
     *
     * The lookup happens **before** the insert on purpose: the registry is unique
     * by name, and a blind `REPLACE` would delete the existing row (and cascade
     * away every `transaction_tags` link) when the names match but uids differ.
     */
    fun findOrCreate(name: String, colorCode: String? = null): Tag? {
        val normalized = TagCodec.normalizeName(name) ?: return null
        getRecordByName(normalized)?.let { return it }
        val tag = Tag(normalized).apply { this.colorCode = colorCode }
        addRecord(tag, UpdateMethod.Insert)
        return tag
    }

    /**
     * Renames [tag] to [newName], propagating the change to the relation table in
     * the same transaction (the relation denormalises the name for search).
     * Returns the updated tag, or the original when the new name is invalid or
     * already taken by another tag.
     */
    fun rename(tag: Tag, newName: String): Tag {
        val normalized = TagCodec.normalizeName(newName) ?: return tag
        if (normalized.equals(tag.name, ignoreCase = true)) return tag
        val clash = getRecordByName(normalized)
        if (clash != null && clash.uid != tag.uid) {
            Timber.w("Refusing to rename tag '%s' to '%s': already exists", tag.name, normalized)
            return tag
        }
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put(TagEntry.COLUMN_NAME, normalized) }
            db.update(TagEntry.TABLE_NAME, values, "${TagEntry.COLUMN_UID} = ?", arrayOf(tag.uid))
            val linkValues = ContentValues().apply { put(TransactionTagEntry.COLUMN_TAG, normalized) }
            db.update(
                TransactionTagEntry.TABLE_NAME,
                linkValues,
                "${TransactionTagEntry.COLUMN_TAG_UID} = ?",
                arrayOf(tag.uid)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        tag.name = normalized
        return tag
    }

    /**
     * Deletes a tag and (via the cascading foreign key) all of its transaction
     * links. Returns the number of registry rows removed.
     */
    fun deleteTag(name: String): Int {
        val normalized = TagCodec.normalizeName(name) ?: return 0
        return db.delete(TagEntry.TABLE_NAME, "${TagEntry.COLUMN_NAME} = ?", arrayOf(normalized))
    }

    /** Removes registry rows that no transaction references any more. */
    fun deleteUnused(): Int {
        val sql = "DELETE FROM ${TagEntry.TABLE_NAME} WHERE ${TagEntry.COLUMN_UID} NOT IN " +
                "(SELECT DISTINCT ${TransactionTagEntry.COLUMN_TAG_UID} FROM " +
                "${TransactionTagEntry.TABLE_NAME} WHERE ${TransactionTagEntry.COLUMN_TAG_UID} IS NOT NULL)"
        val statement = db.compileStatement(sql)
        return statement.executeUpdateDelete()
    }

    /**
     * Replaces the tag set of [transactionUid] with [names] (idempotent: the old
     * rows for the transaction are removed first, so re-saving never duplicates).
     * Returns the number of tags that stuck.
     */
    fun setTagsForTransaction(transactionUid: String, names: Collection<String>): Int {
        if (transactionUid.isEmpty()) return 0
        val normalized = names
            .mapNotNull { TagCodec.normalizeName(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        db.beginTransaction()
        try {
            db.delete(
                TransactionTagEntry.TABLE_NAME,
                "${TransactionTagEntry.COLUMN_TRANSACTION_UID} = ?",
                arrayOf(transactionUid)
            )
            var written = 0
            for (name in normalized) {
                val tag = findOrCreate(name) ?: continue
                val values = ContentValues().apply {
                    put(TransactionTagEntry.COLUMN_TRANSACTION_UID, transactionUid)
                    put(TransactionTagEntry.COLUMN_TAG, tag.name)
                    put(TransactionTagEntry.COLUMN_TAG_UID, tag.uid)
                }
                db.insertWithOnConflict(
                    TransactionTagEntry.TABLE_NAME,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                written++
            }
            db.setTransactionSuccessful()
            return written
        } finally {
            db.endTransaction()
        }
    }

    /** Tag names attached to [transactionUid], ordered by name. */
    fun getTagNamesForTransaction(transactionUid: String): List<String> {
        val out = mutableListOf<String>()
        db.query(
            TransactionTagEntry.TABLE_NAME,
            arrayOf(TransactionTagEntry.COLUMN_TAG),
            "${TransactionTagEntry.COLUMN_TRANSACTION_UID} = ?",
            arrayOf(transactionUid),
            null,
            null,
            "${TransactionTagEntry.COLUMN_TAG} ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * Parses `#tag` tokens out of the transaction's own text (description, notes
     * and every split memo) and mirrors them into the relation table.
     *
     * This is the App → DB half of the dual-write contract: the text stays the
     * source of truth (desktop compatibility), the tables are the queryable
     * mirror.
     */
    fun syncFromTransaction(transaction: Transaction): Int {
        // The relation has a cascading FK on `transactions.uid`, so mirroring tags
        // for a transaction that is not (yet) in the table would throw. An update
        // of a deleted transaction is legitimately a no-op elsewhere, so keep it a
        // no-op here too instead of turning it into a crash.
        if (!transactionExists(transaction.uid)) {
            Timber.d("Skipping tag sync for unknown transaction %s", transaction.uid)
            return 0
        }
        val texts = ArrayList<String>(2 + transaction.splits.size)
        texts.add(transaction.description)
        texts.add(transaction.notes)
        for (split in transaction.splits) {
            texts.add(split.memo)
        }
        val names = texts.flatMap { TagCodec.extract(it) }
        return setTagsForTransaction(transaction.uid, names)
    }

    private fun transactionExists(uid: String): Boolean {
        if (uid.isEmpty()) return false
        db.rawQuery(
            "SELECT 1 FROM ${TransactionEntry.TABLE_NAME} WHERE ${TransactionEntry.COLUMN_UID} = ? LIMIT 1",
            arrayOf(uid)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    /** How many distinct transactions carry the tag [name]. */
    fun getTransactionCountForTag(name: String): Long {
        val normalized = TagCodec.normalizeName(name) ?: return 0L
        var count = 0L
        db.rawQuery(
            "SELECT COUNT(DISTINCT ${TransactionTagEntry.COLUMN_TRANSACTION_UID}) FROM " +
                    TransactionTagEntry.TABLE_NAME + " WHERE ${TransactionTagEntry.COLUMN_TAG} = ?",
            arrayOf(normalized)
        ).use { cursor ->
            if (cursor.moveToFirst()) count = cursor.getLong(0)
        }
        return count
    }

    /** Tag usage for statistics: (name, number of transactions), most used first. */
    fun getUsageCounts(): List<TagUsage> {
        val sql = "SELECT tt.${TransactionTagEntry.COLUMN_TAG} AS tag, " +
                "COUNT(DISTINCT tt.${TransactionTagEntry.COLUMN_TRANSACTION_UID}) AS cnt " +
                "FROM ${TransactionTagEntry.TABLE_NAME} tt " +
                "JOIN ${TransactionEntry.TABLE_NAME} t ON t.${TransactionEntry.COLUMN_UID} = " +
                "tt.${TransactionTagEntry.COLUMN_TRANSACTION_UID} " +
                "GROUP BY tag ORDER BY cnt DESC, tag ASC"
        val out = mutableListOf<TagUsage>()
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(TagUsage(cursor.getString(0) ?: "", cursor.getLong(1)))
            }
        }
        return out
    }

    @Throws(IOException::class)
    override fun close() {
        super.close()
    }

    companion object {
        private val entryColumns = arrayOf(
            TagEntry.COLUMN_NAME,
            TagEntry.COLUMN_COLOR
        )
        private const val INDEX_COLUMN_NAME = 0
        private const val INDEX_COLUMN_COLOR = INDEX_COLUMN_NAME + 1

        /**
         * Adapter backed by the currently active book, re-derived on every access
         * so a book switch is picked up automatically.
         */
        val instance: TagsDbAdapter get() = TagsDbAdapter(AccountsDbAdapter.instance.holder)
    }
}

/** A tag and how many transactions carry it (feature ⑧ statistics). */
data class TagUsage(val name: String, val count: Long)
