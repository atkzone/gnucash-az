package org.gnucash.android.ui.search

/**
 * The transactions ticked for a bulk action.
 *
 * Deliberately holds UIDs rather than cursor positions: the result list is
 * re-queried on every sort change, tag filter and after each bulk action, so a
 * position-keyed selection would silently point at different rows. A UID-keyed
 * one survives reloads, and rows that drop out of the result set simply stop
 * being rendered (they stay selected, which is the safe direction: the user can
 * still see the count they are about to act on).
 */
class SearchSelection {

    private val uids = LinkedHashSet<String>()

    val size: Int get() = uids.size
    val isEmpty: Boolean get() = uids.isEmpty()

    /** Stable copy, in the order rows were ticked. */
    fun snapshot(): List<String> = uids.toList()

    fun isSelected(uid: String): Boolean = uids.contains(uid)

    fun clear() {
        uids.clear()
    }

    fun selectAll(all: Collection<String>) {
        uids.addAll(all)
    }

    /** @return `true` when the uid is selected afterwards. */
    fun toggle(uid: String): Boolean {
        if (uids.remove(uid)) return false
        uids.add(uid)
        return true
    }
}
