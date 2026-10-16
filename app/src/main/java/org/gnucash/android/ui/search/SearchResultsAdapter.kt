package org.gnucash.android.ui.search

import android.database.Cursor
import android.view.LayoutInflater
import android.view.ViewGroup
import org.gnucash.android.databinding.CardviewTransactionBinding
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.ui.adapter.CursorRecyclerAdapter

class SearchResultsAdapter(
    cursor: Cursor?,
    private val isDoubleEntry: Boolean = true,
    private val callback: SearchResultCallback,
    private val onTagClick: TagFilterCallback = {}
) : CursorRecyclerAdapter<SearchResultViewHolder>(cursor) {

    /** Rows ticked for a bulk action. UID-keyed so reloads cannot shift it. */
    val selection = SearchSelection()

    /** True while the list shows check boxes. */
    var isSelectionMode: Boolean = false
        private set

    /** Fired whenever the tick count changes (drives the action-bar title). */
    var onSelectionChanged: (() -> Unit)? = null

    /** Enters selection mode, optionally ticking [uid] (the long-pressed row). */
    fun startSelection(uid: String?) {
        isSelectionMode = true
        if (uid != null) selection.toggle(uid)
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun stopSelection() {
        isSelectionMode = false
        selection.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun toggle(uid: String) {
        selection.toggle(uid)
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    /** Ticks every row in the current cursor, not just the ones on screen. */
    fun selectAll() {
        selection.selectAll(visibleUids())
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    /** UIDs of every row in the cursor, in list order. */
    fun visibleUids(): List<String> {
        val cursor = cursor ?: return emptyList()
        val column = cursor.getColumnIndex(TransactionEntry.COLUMN_UID)
        if (column < 0) return emptyList()
        val uids = ArrayList<String>(cursor.count)
        val current = cursor.position
        try {
            cursor.moveToPosition(-1)
            while (cursor.moveToNext()) {
                cursor.getString(column)?.let { uids.add(it) }
            }
        } finally {
            cursor.moveToPosition(current)
        }
        return uids
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = CardviewTransactionBinding.inflate(inflater, parent, false)
        return SearchResultViewHolder(
            binding,
            isDoubleEntry,
            callback,
            onTagClick,
            { isSelectionMode },
            { uid -> selection.isSelected(uid) },
            { uid -> toggle(uid) },
            { uid -> startSelection(uid) }
        )
    }

    override fun onBindViewHolderCursor(holder: SearchResultViewHolder, cursor: Cursor) {
        holder.bind(cursor)
    }
}