package org.gnucash.android.db.adapter

import android.content.ContentValues
import android.database.Cursor
import org.gnucash.android.db.DatabaseHolder
import org.gnucash.android.db.DatabaseSchema.TransactionTemplateEntry
import org.gnucash.android.ui.template.TransactionTemplate
import org.gnucash.android.ui.template.TransactionTemplateCodec
import timber.log.Timber

/**
 * Persistence for locally saved *transaction* templates (feature ①d).
 *
 * Mirrors [SearchTemplateDbAdapter]: [TransactionTemplate] is not a `BaseModel`
 * (no `uid`, never exported to GnuCash XML, never part of the sync merge), so
 * this deliberately does **not** extend `DatabaseAdapter`. The whole payload
 * lives in one opaque JSON column — see [TransactionTemplateCodec].
 *
 * The adapter is stateless and cheap to build; the database is owned by the
 * passed-in [DatabaseHolder].
 */
class TransactionTemplateDbAdapter(val holder: DatabaseHolder) {

    private val db = holder.db

    /** Inserts a new template and writes the assigned id/timestamps back. */
    fun insert(template: TransactionTemplate): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(TransactionTemplateEntry.COLUMN_NAME, template.name)
            put(TransactionTemplateEntry.COLUMN_PAYLOAD, TransactionTemplateCodec.encode(template))
            put(TransactionTemplateEntry.COLUMN_SORT_ORDER, template.sortOrder)
            put(TransactionTemplateEntry.COLUMN_CREATED_AT, now)
            put(TransactionTemplateEntry.COLUMN_UPDATED_AT, now)
        }
        val id = db.insert(TransactionTemplateEntry.TABLE_NAME, null, values)
        template.id = id
        template.createdAt = now
        template.updatedAt = now
        template.decodeFailed = false
        return id
    }

    /** Updates an existing template (matched by id). Returns the number of rows. */
    fun update(template: TransactionTemplate): Int {
        require(template.id > 0L) { "Cannot update a transaction template without a row id" }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(TransactionTemplateEntry.COLUMN_NAME, template.name)
            put(TransactionTemplateEntry.COLUMN_PAYLOAD, TransactionTemplateCodec.encode(template))
            put(TransactionTemplateEntry.COLUMN_SORT_ORDER, template.sortOrder)
            put(TransactionTemplateEntry.COLUMN_UPDATED_AT, now)
        }
        val rows = db.update(
            TransactionTemplateEntry.TABLE_NAME,
            values,
            "${TransactionTemplateEntry.COLUMN_ID} = ?",
            arrayOf(template.id.toString())
        )
        template.updatedAt = now
        return rows
    }

    /**
     * Inserts when the template is new (id == 0), otherwise updates. If the id
     * points at a row that no longer exists, falls back to inserting rather than
     * silently losing the save.
     */
    fun save(template: TransactionTemplate): TransactionTemplate {
        if (template.id > 0L && update(template) > 0) return template
        insert(template)
        return template
    }

    /**
     * Upsert by **name** — backing the "save as …" flow where re-using an
     * existing name is meant to overwrite that entry (matching the saved-search
     * behaviour). Returns the stored row id.
     */
    fun saveByName(template: TransactionTemplate): Long {
        val existing = getRecordByName(template.name)
        if (existing != null) {
            template.id = existing.id
            template.sortOrder = existing.sortOrder
            update(template)
            return existing.id
        }
        template.sortOrder = nextSortOrder()
        return insert(template)
    }

    fun getRecord(id: Long): TransactionTemplate? =
        query("${TransactionTemplateEntry.COLUMN_ID} = ?", arrayOf(id.toString())).firstOrNull()

    fun getRecordByName(name: String): TransactionTemplate? =
        query("${TransactionTemplateEntry.COLUMN_NAME} = ?", arrayOf(name)).firstOrNull()

    /** All templates, ordered by sort order then name. */
    fun getAll(): List<TransactionTemplate> = query(null, null)

    fun getCount(): Long {
        db.rawQuery("SELECT COUNT(*) FROM ${TransactionTemplateEntry.TABLE_NAME}", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** Appends after the current last template. */
    fun nextSortOrder(): Int = (getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1

    fun delete(id: Long): Int =
        db.delete(
            TransactionTemplateEntry.TABLE_NAME,
            "${TransactionTemplateEntry.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )

    /**
     * Bulk delete backing "管理 → 删除所选".
     *
     * An empty selection is a deliberate no-op (a `WHERE _id IN ()` would be a
     * syntax error) and ids are bound as parameters — never string-built.
     */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(", ") { "?" }
        return db.delete(
            TransactionTemplateEntry.TABLE_NAME,
            "${TransactionTemplateEntry.COLUMN_ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    fun deleteAll(): Int = db.delete(TransactionTemplateEntry.TABLE_NAME, null, null)

    private fun query(selection: String?, selectionArgs: Array<String>?): List<TransactionTemplate> {
        val out = mutableListOf<TransactionTemplate>()
        val order = "${TransactionTemplateEntry.COLUMN_SORT_ORDER} ASC, " +
                "${TransactionTemplateEntry.COLUMN_NAME} ASC"
        db.query(TransactionTemplateEntry.TABLE_NAME, null, selection, selectionArgs, null, null, order)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(buildModelInstance(cursor))
                }
            }
        return out
    }

    private fun buildModelInstance(cursor: Cursor): TransactionTemplate {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(TransactionTemplateEntry.COLUMN_ID))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(TransactionTemplateEntry.COLUMN_NAME)) ?: ""
        val json = cursor.getString(cursor.getColumnIndexOrThrow(TransactionTemplateEntry.COLUMN_PAYLOAD))
        val sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(TransactionTemplateEntry.COLUMN_SORT_ORDER))
        val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(TransactionTemplateEntry.COLUMN_CREATED_AT))
        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(TransactionTemplateEntry.COLUMN_UPDATED_AT))

        // A corrupt/legacy payload must not take the whole list down: keep the row
        // (so it can still be renamed/deleted) but flag it so the UI refuses to
        // apply it — silently posting an empty transaction would be worse.
        var decodeFailed = false
        val template = try {
            TransactionTemplateCodec.decode(json)
        } catch (e: Exception) {
            Timber.e(e, "Could not decode transaction template #%d (%s); keeping row as unusable", id, name)
            decodeFailed = true
            TransactionTemplate()
        }

        return template.apply {
            this.name = name
            this.id = id
            this.sortOrder = sortOrder
            this.createdAt = createdAt
            this.updatedAt = updatedAt
            this.decodeFailed = decodeFailed
        }
    }

    companion object {
        /**
         * Adapter backed by the currently active book, re-derived on every access
         * so a book switch is picked up automatically.
         */
        val instance: TransactionTemplateDbAdapter
            get() = TransactionTemplateDbAdapter(AccountsDbAdapter.instance.holder)
    }
}
