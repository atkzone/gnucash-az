package org.gnucash.android.ui.search

import android.content.Context
import android.database.Cursor
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.gnucash.android.R
import org.gnucash.android.databinding.CardviewTransactionBinding
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TagsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Transaction
import org.gnucash.android.ui.util.displayBalance
import org.gnucash.android.util.TagCodec

enum class SearchResultAction {
    View,
    Edit,
    Delete,
    Duplicate,
    Move
}

typealias SearchResultCallback = (Transaction, SearchResultAction) -> Unit

/** Invoked when the user taps a tag chip under a search result. */
typealias TagFilterCallback = (String) -> Unit

class SearchResultViewHolder(
    binding: CardviewTransactionBinding,
    private val isDoubleEntry: Boolean = true,
    private val callback: SearchResultCallback,
    private val onTagClick: TagFilterCallback = {},
    /** The list is in bulk-selection mode: ticks visible, taps tick instead of open. */
    private val isSelectionMode: () -> Boolean = { false },
    /** uid -> is that row ticked. */
    private val isSelected: (String) -> Boolean = { false },
    /** Tap while in selection mode. */
    private val onSelectionToggle: (String) -> Unit = {},
    /** Long press outside selection mode: enter it with this row ticked. */
    private val onSelectionStart: (String) -> Unit = {}
) : RecyclerView.ViewHolder(binding.root),
    PopupMenu.OnMenuItemClickListener {
    private val primaryText: TextView = binding.listItem2Lines.primaryText
    private val secondaryText: TextView = binding.listItem2Lines.secondaryText
    private val transactionAmount: TextView = binding.transactionAmount
    private val optionsMenu: ImageView = binding.optionsMenu
    private val selectionCheck: CheckBox = binding.selectionCheck

    // Tag chips (feature ⑧): a horizontal strip under the date.
    private val tagChipsContainer: LinearLayout = binding.tagChips
    private val tagChipsScroll: android.view.View = binding.tagChipsScroll

    //these views are not used in the compact view, hence the nullability
    private val transactionDate: TextView = binding.transactionDate
    private val editTransaction: ImageView = binding.editTransaction

    private var transaction: Transaction? = null
    private val accountsDbAdapter: AccountsDbAdapter = AccountsDbAdapter.instance
    private val transactionsDbAdapter: TransactionsDbAdapter =
        accountsDbAdapter.transactionsDbAdapter
    private val tagsDbAdapter: TagsDbAdapter = TagsDbAdapter(accountsDbAdapter.holder)

    @ColorInt
    private val colorBalanceZero: Int = transactionAmount.currentTextColor

    init {
        optionsMenu.setOnClickListener { v ->
            val popupMenu = PopupMenu(v.context, v)
            popupMenu.setOnMenuItemClickListener(this@SearchResultViewHolder)
            val inflater = popupMenu.menuInflater
            val menu = popupMenu.menu
            inflater.inflate(R.menu.transactions_context_menu, menu)
            menu.findItem(R.id.menu_edit).isVisible = false
            menu.findItem(R.id.menu_move).isVisible = false
            popupMenu.show()
        }

        itemView.setOnClickListener {
            val transaction = transaction ?: return@setOnClickListener
            if (isSelectionMode()) {
                // In selection mode a tap ticks the row instead of opening it, so
                // every row stays reachable without reaching for the check box.
                onSelectionToggle(transaction.uid)
            } else {
                callback(transaction, SearchResultAction.View)
            }
        }

        itemView.setOnLongClickListener {
            val transaction = transaction ?: return@setOnLongClickListener false
            if (isSelectionMode()) {
                onSelectionToggle(transaction.uid)
            } else {
                onSelectionStart(transaction.uid)
            }
            true
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val transaction = transaction ?: return false

        return when (item.itemId) {
            R.id.menu_delete -> {
                callback(transaction, SearchResultAction.Delete)
                true
            }

            R.id.menu_duplicate -> {
                callback(transaction, SearchResultAction.Duplicate)
                true
            }

            R.id.menu_edit -> {
                callback(transaction, SearchResultAction.Edit)
                true
            }

            R.id.menu_move -> {
                callback(transaction, SearchResultAction.Move)
                true
            }

            else -> false
        }
    }

    fun bind(cursor: Cursor) {
        val context = itemView.context
        val transaction = transactionsDbAdapter.buildModelInstance(cursor)
        this.transaction = transaction

        val selectionMode = isSelectionMode()
        val selected = selectionMode && isSelected(transaction.uid)
        selectionCheck.isVisible = selectionMode
        selectionCheck.isChecked = selected
        // Dim the rows that will NOT be touched: the pending action's reach is
        // otherwise only visible as a tick count in the action bar.
        itemView.alpha = if (selectionMode && !selected) 0.55f else 1f

        primaryText.text = transaction.description
        transactionDate.text = formatPrettyDate(context, transaction.datePosted)
        transactionAmount.isVisible = false

        if (isDoubleEntry) {
            secondaryText.isVisible = true
            editTransaction.isVisible = true

            val splits = transaction.splits
            var text: String? = ""
            var error: String? = null

            if (splits.size == 2) {
                val accountUID = transaction.defaultAccountUID
                if (accountUID != null) {
                    text = accountsDbAdapter.getFullyQualifiedAccountName(accountUID)

                    val amount = transaction.getBalance(accountUID)
                    transactionAmount.displayBalance(amount, colorBalanceZero)
                    transactionAmount.isVisible = true
                }
                if (text.isNullOrEmpty()) {
                    text = context.getString(R.string.label_split_count, splits.size)
                    error = context.getString(R.string.imbalance_account_name)
                }
            } else if (splits.size > 2) {
                text = context.getString(R.string.label_split_count, splits.size)
            }
            secondaryText.text = text
            secondaryText.error = error

            editTransaction.setOnClickListener {
                callback(transaction, SearchResultAction.Edit)
            }
        } else {
            secondaryText.isVisible = false
            editTransaction.isVisible = false
        }

        // Feature ⑧: surface the tags the transaction carries as clickable chips.
        renderTags(tagsDbAdapter.getTagNamesForTransaction(transaction.uid))
    }

    /**
     * Renders [tagNames] as chips and hides the strip when there is nothing to
     * show. Kept separate from [bind] so it can be asserted without a cursor.
     */
    fun renderTags(tagNames: List<String>) {
        tagChipsContainer.removeAllViews()
        tagChipsScroll.isVisible = tagNames.isNotEmpty()
        if (tagNames.isEmpty()) return

        val inflater = LayoutInflater.from(itemView.context)
        for (name in tagNames) {
            val chip = inflater.inflate(R.layout.item_tag_chip, tagChipsContainer, false) as TextView
            chip.text = TagCodec.PREFIX + name
            chip.contentDescription = itemView.context.getString(R.string.search_filter_tag, name)
            chip.setOnClickListener { onTagClick(name) }
            tagChipsContainer.addView(chip)
        }
    }

    private fun formatPrettyDate(context: Context, time: Long): String {
        return DateUtils.getRelativeDateTimeString(
            context,
            time,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.WEEK_IN_MILLIS,
            0
        ).toString()
    }
}