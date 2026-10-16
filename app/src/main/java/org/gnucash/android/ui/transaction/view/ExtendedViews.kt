package org.gnucash.android.ui.transaction.view

/**
 * Every list operation behind "设置扩展显示", as pure functions over
 * `(views, defaultId)`.
 *
 * Keeping them here (instead of in the fragment) is what makes the awkward rules
 * assertable without an Activity: the default pointer must never dangle, deleting
 * the default view has to hand the pointer to a survivor, and a rename that
 * arrives blank must not silently wipe the name.
 */
object ExtendedViews {

    /** @return the trimmed name, or `null` when nothing usable was typed. */
    fun normalizedName(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A free id, shaped `v1`, `v2`, … so a stored payload stays readable.
     *
     * Derived from the **highest** existing suffix rather than from the count, so
     * deleting `v2` and adding a view cannot reuse a still-live id.
     */
    fun newId(views: List<ExtendedView>): String {
        val used = views.mapTo(HashSet()) { it.id }
        var next = 1
        for (view in views) {
            val suffix = view.id.removePrefix(ID_PREFIX).toIntOrNull() ?: continue
            if (suffix >= next) next = suffix + 1
        }
        while (ID_PREFIX + next in used) next++
        return ID_PREFIX + next
    }

    /**
     * Appends [view]; a view list with no default yet adopts the newcomer.
     *
     * @return the new list and the new default pointer.
     */
    fun add(
        views: List<ExtendedView>,
        defaultId: String?,
        view: ExtendedView
    ): Pair<List<ExtendedView>, String?> {
        val added = views + view
        return added to coerceDefault(added, defaultId)
    }

    /** Renames [id]; unknown id or a blank name leaves the list exactly as it was. */
    fun rename(views: List<ExtendedView>, id: String, rawName: String?): List<ExtendedView> {
        val name = normalizedName(rawName) ?: return views
        return views.map { if (it.id == id) it.copy(name = name) else it }
    }

    /**
     * Drops [id] and, when it was the default, points the default at a survivor.
     *
     * @return the new list and the new default pointer (`null` once nothing is left).
     */
    fun remove(
        views: List<ExtendedView>,
        defaultId: String?,
        id: String
    ): Pair<List<ExtendedView>, String?> {
        val remaining = views.filterNot { it.id == id }
        if (remaining.size == views.size) return views to defaultId
        return remaining to coerceDefault(remaining, defaultId)
    }

    /**
     * Marks [id] as the default extended view; `null` clears the pointer.
     *
     * An unknown id is refused rather than stored — the alternative is a pointer
     * that only shows up as "the wrong view was used" much later.
     */
    fun setDefault(views: List<ExtendedView>, defaultId: String?, id: String?): String? {
        if (id == null) return null
        if (views.none { it.id == id }) return defaultId
        return id
    }

    /** The view the list renders with, or `null` when there is nothing to render. */
    fun active(views: List<ExtendedView>, defaultId: String?): ExtendedView? {
        val id = coerceDefault(views, defaultId) ?: return null
        return views.firstOrNull { it.id == id }
    }

    private fun coerceDefault(views: List<ExtendedView>, defaultId: String?): String? {
        if (defaultId != null && views.any { it.id == defaultId }) return defaultId
        return views.firstOrNull()?.id
    }

    private const val ID_PREFIX = "v"
}
