package org.gnucash.android.ui.search

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.gnucash.android.R
import org.gnucash.android.db.adapter.SearchTemplateDbAdapter
import timber.log.Timber

/**
 * Shared dialogs behind the "常用搜索" feature — save / apply / rename / batch
 * delete — used by **both** the "全文搜索" and the "高级搜索" screens, so the two
 * entry points cannot drift apart.
 *
 * Every list is scoped to a [SearchTemplateKind]: a full-text favourite is not a
 * criteria set and must not be offered on the advanced screen (and vice versa).
 * The kind lives inside the stored payload, so the scoping is a filter, not a
 * second table.
 */
object SearchFavoritesUi {

    /** A full-text favourite keeps the raw query text, not the compiled SQL. */
    fun fullTextTemplate(name: String, query: String): SearchTemplate =
        SearchTemplate(name = name, kind = SearchTemplateKind.FullText, query = query)

    /** Only the entries saved from a given screen's "常用搜索". */
    fun load(kind: SearchTemplateKind): List<SearchTemplate> = try {
        SearchTemplateDbAdapter.instance.getByKind(kind)
    } catch (e: Exception) {
        // Never fail silently: a list that could not be read must say so.
        Timber.e(e, "Failed to load favourite searches")
        emptyList()
    }

    /**
     * Saves [template]; a same name **of the same kind** overwrites in place so
     * the list does not fill up with duplicates. Returns whether it persisted.
     */
    fun save(context: Context, template: SearchTemplate): Boolean = try {
        val adapter = SearchTemplateDbAdapter.instance
        val existing = adapter.getRecordByName(template.name)
        if (existing != null && existing.kind == template.kind) {
            existing.form = template.form
            existing.query = template.query
            adapter.save(existing)
        } else {
            if (existing == null) template.sortOrder = adapter.nextSortOrder()
            adapter.save(template)
        }
        toast(context, R.string.search_templates_saved)
        true
    } catch (e: Exception) {
        Timber.e(e, "Failed to save favourite search '%s'", template.name)
        toast(context, R.string.search_templates_save_failed)
        false
    }

    /** The "常用搜索" list: tap an entry to apply it, or manage the whole list. */
    fun showList(context: Context, kind: SearchTemplateKind, onApply: (SearchTemplate) -> Unit) {
        val entries = load(kind)
        if (entries.isEmpty()) {
            toast(context, R.string.search_templates_empty)
            return
        }
        val names: Array<CharSequence> = Array(entries.size) { entries[it].name }
        AlertDialog.Builder(context)
            .setTitle(R.string.search_templates_load)
            .setItems(names) { _, which -> onApply(entries[which]) }
            .setNeutralButton(R.string.search_templates_manage) { _, _ -> showManage(context, kind) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The "管理" dialog: a multi-choice list with "改名" (exactly one selected)
     * and "删除所选" (batch delete). Deleting asks for confirmation first — the
     * action is destructive and cannot be undone.
     */
    fun showManage(context: Context, kind: SearchTemplateKind) {
        val entries = load(kind)
        if (entries.isEmpty()) {
            toast(context, R.string.search_templates_empty)
            return
        }
        val names: Array<CharSequence> = Array(entries.size) { entries[it].name }
        val checked = BooleanArray(entries.size)

        AlertDialog.Builder(context)
            .setTitle(R.string.search_templates_manage)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.search_templates_delete_selected) { _, _ ->
                val selected = entries.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    toast(context, R.string.search_templates_none_selected)
                } else {
                    confirmDelete(context, selected)
                }
            }
            .setNeutralButton(R.string.search_templates_rename) { _, _ ->
                val selected = entries.filterIndexed { index, _ -> checked[index] }
                if (selected.size != 1) {
                    // Renaming is unambiguous only for a single row.
                    toast(context, R.string.search_templates_one_selected)
                } else {
                    promptName(context, R.string.search_templates_rename, selected[0].name) { name ->
                        rename(context, selected[0], name)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks for a name (used for "保存为常用搜索" and for "改名"). */
    fun promptName(
        context: Context,
        titleRes: Int,
        current: String?,
        onName: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            hint = context.getString(R.string.search_templates_name_hint)
            if (current != null) setText(current)
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(withPadding(context, input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast(context, R.string.search_templates_name_required)
                } else {
                    onName(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(context: Context, selected: List<SearchTemplate>) {
        AlertDialog.Builder(context)
            .setTitle(R.string.search_templates_delete_selected)
            .setMessage(R.string.search_templates_delete_confirm)
            .setPositiveButton(R.string.search_templates_delete) { _, _ -> delete(context, selected) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete(context: Context, selected: List<SearchTemplate>) {
        try {
            SearchTemplateDbAdapter.instance.delete(selected.map { it.id })
            toast(context, R.string.search_templates_deleted)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete %d favourite search(es)", selected.size)
            toast(context, R.string.search_templates_delete_failed)
        }
    }

    private fun rename(context: Context, template: SearchTemplate, name: String) {
        template.name = name
        try {
            SearchTemplateDbAdapter.instance.save(template)
            toast(context, R.string.search_templates_saved)
        } catch (e: Exception) {
            Timber.e(e, "Failed to rename favourite search #%d", template.id)
            toast(context, R.string.search_templates_save_failed)
        }
    }

    /** Wraps a view so it is not glued to the dialog edges. */
    private fun withPadding(context: Context, view: EditText): FrameLayout {
        val container = FrameLayout(context)
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)
        container.addView(view)
        return container
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
