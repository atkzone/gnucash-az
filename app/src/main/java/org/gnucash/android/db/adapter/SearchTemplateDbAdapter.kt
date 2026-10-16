package org.gnucash.android.db.adapter

import android.content.ContentValues
import android.database.Cursor
import org.gnucash.android.db.DatabaseHolder
import org.gnucash.android.db.DatabaseSchema.SearchTemplateEntry
import org.gnucash.android.model.Account
import org.gnucash.android.ui.search.SearchForm
import org.gnucash.android.ui.search.SearchTemplate
import org.gnucash.android.ui.search.SearchTemplateCodec
import org.gnucash.android.ui.search.SearchTemplateKind
import timber.log.Timber

/**
 * Persistence for locally saved search configurations (feature ①a).
 *
 * Unlike the domain adapters this one does **not** extend `DatabaseAdapter`:
 * [SearchTemplate] is not a `BaseModel` (no `uid`, never exported to GnuCash XML).
 * The whole criteria set lives in a single opaque JSON column, so criteria can
 * evolve without a schema change — see [SearchTemplateCodec].
 *
 * The adapter is stateless (holds no cursors/statements); callers may create it
 * freely. The database itself is owned by the passed-in [DatabaseHolder].
 */
class SearchTemplateDbAdapter(
    val holder: DatabaseHolder,
    /**
     * Resolves a stored account `uid` back to an [Account] when decoding a
     * template. Defaults to the live book database. Kept as a lambda so it is
     * only invoked at decode time (never at construction).
     */
    private val accountResolver: (String) -> Account? = { uid ->
        AccountsDbAdapter.instance.getRecordOrNull(uid)
    }
) {
    private val db = holder.db

    /** Inserts a new template and writes the assigned id/createdAt back. */
    fun insert(template: SearchTemplate): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(SearchTemplateEntry.COLUMN_NAME, template.name)
            put(SearchTemplateEntry.COLUMN_FORM, SearchTemplateCodec.encode(template))
            put(SearchTemplateEntry.COLUMN_SORT_ORDER, template.sortOrder)
            put(SearchTemplateEntry.COLUMN_CREATED_AT, now)
            put(SearchTemplateEntry.COLUMN_UPDATED_AT, now)
        }
        val id = db.insert(SearchTemplateEntry.TABLE_NAME, null, values)
        template.id = id
        template.createdAt = now
        template.updatedAt = now
        return id
    }

    /** Updates an existing template (matched by id). Returns the number of rows. */
    fun update(template: SearchTemplate): Int {
        require(template.id > 0L) { "Cannot update a template without a row id" }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(SearchTemplateEntry.COLUMN_NAME, template.name)
            put(SearchTemplateEntry.COLUMN_FORM, SearchTemplateCodec.encode(template))
            put(SearchTemplateEntry.COLUMN_SORT_ORDER, template.sortOrder)
            put(SearchTemplateEntry.COLUMN_UPDATED_AT, now)
        }
        val rows = db.update(
            SearchTemplateEntry.TABLE_NAME,
            values,
            "${SearchTemplateEntry.COLUMN_ID} = ?",
            arrayOf(template.id.toString())
        )
        template.updatedAt = now
        return rows
    }

    /**
     * Inserts when the template is new (id == 0), otherwise updates. If the id
     * points at a row that no longer exists, falls back to inserting a new row
     * (rather than silently losing the save).
     */
    fun save(template: SearchTemplate): SearchTemplate {
        if (template.id > 0L && update(template) > 0) return template
        insert(template)
        return template
    }

    fun getRecord(id: Long): SearchTemplate? =
        query("${SearchTemplateEntry.COLUMN_ID} = ?", arrayOf(id.toString())).firstOrNull()

    fun getRecordByName(name: String): SearchTemplate? =
        query("${SearchTemplateEntry.COLUMN_NAME} = ?", arrayOf(name)).firstOrNull()

    /** All templates, ordered by [SearchTemplateEntry.COLUMN_SORT_ORDER] then name. */
    fun getAll(): List<SearchTemplate> = query(null, null)

    /**
     * Only the entries belonging to one screen's "常用搜索" list.
     *
     * The kind lives inside the opaque payload (not a column), so the filter is
     * applied after decoding — the list is small and bounded by what a user saved.
     */
    fun getByKind(kind: SearchTemplateKind): List<SearchTemplate> =
        getAll().filter { it.kind == kind }

    fun getCount(): Long {
        db.rawQuery("SELECT COUNT(*) FROM ${SearchTemplateEntry.TABLE_NAME}", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** Appends after the current last template. */
    fun nextSortOrder(): Int = (getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1

    fun delete(id: Long): Int =
        db.delete(SearchTemplateEntry.TABLE_NAME, "${SearchTemplateEntry.COLUMN_ID} = ?", arrayOf(id.toString()))

    /**
     * Bulk delete backing the "常用搜索 → 管理 → 删除所选" action.
     *
     * An empty selection is a deliberate no-op (a `WHERE _id IN ()` would be a
     * syntax error), and the ids are bound as parameters — no string building
     * from user data.
     */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(", ") { "?" }
        return db.delete(
            SearchTemplateEntry.TABLE_NAME,
            "${SearchTemplateEntry.COLUMN_ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    fun deleteAll(): Int = db.delete(SearchTemplateEntry.TABLE_NAME, null, null)

    private fun query(selection: String?, selectionArgs: Array<String>?): List<SearchTemplate> {
        val out = mutableListOf<SearchTemplate>()
        val order = "${SearchTemplateEntry.COLUMN_SORT_ORDER} ASC, ${SearchTemplateEntry.COLUMN_NAME} ASC"
        db.query(SearchTemplateEntry.TABLE_NAME, null, selection, selectionArgs, null, null, order)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(buildModelInstance(cursor))
                }
            }
        return out
    }

    private fun buildModelInstance(cursor: Cursor): SearchTemplate {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(SearchTemplateEntry.COLUMN_ID))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(SearchTemplateEntry.COLUMN_NAME)) ?: ""
        val json = cursor.getString(cursor.getColumnIndexOrThrow(SearchTemplateEntry.COLUMN_FORM))
        val sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(SearchTemplateEntry.COLUMN_SORT_ORDER))
        val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(SearchTemplateEntry.COLUMN_CREATED_AT))
        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(SearchTemplateEntry.COLUMN_UPDATED_AT))

        val kind = SearchTemplateCodec.kindOf(json)
        // A corrupt/legacy payload must not take the whole list down: keep the row
        // (so it can be renamed/deleted) but log it loudly, and let the UI refuse
        // to apply an empty form.
        val form = if (kind == SearchTemplateKind.FullText) {
            SearchForm()
        } else {
            try {
                SearchTemplateCodec.decode(json, accountResolver)
            } catch (e: Exception) {
                Timber.e(e, "Could not decode search template #%d (%s); keeping row with an empty form", id, name)
                SearchForm()
            }
        }

        return SearchTemplate(
            name = name,
            form = form,
            id = id,
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt,
            kind = kind,
            query = SearchTemplateCodec.fullTextQueryOf(json)
        )
    }

    companion object {
        /**
         * Adapter backed by the currently active book, resolved lazily via the
         * accounts adapter (re-derived on every access so a book switch is
         * picked up automatically).
         */
        val instance: SearchTemplateDbAdapter
            get() = SearchTemplateDbAdapter(AccountsDbAdapter.instance.holder)
    }
}
