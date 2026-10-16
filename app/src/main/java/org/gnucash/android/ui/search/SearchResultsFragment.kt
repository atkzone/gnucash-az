package org.gnucash.android.ui.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import org.gnucash.android.R
import org.gnucash.android.app.GnuCashApplication.Companion.isDoubleEntryEnabled
import org.gnucash.android.app.GnuCashApplication.Companion.shouldBackupTransactions
import org.gnucash.android.app.MenuFragment
import org.gnucash.android.app.actionBar
import org.gnucash.android.app.isLandscape
import org.gnucash.android.databinding.FragmentTransactionsListBinding
import org.gnucash.android.model.Transaction
import org.gnucash.android.model.TransactionType
import org.gnucash.android.ui.common.FormActivity
import org.gnucash.android.ui.common.Refreshable
import org.gnucash.android.ui.common.UxArgument
import org.gnucash.android.ui.homescreen.WidgetConfigurationActivity.Companion.updateAllWidgets
import org.gnucash.android.ui.report.ReportsActivity
import org.gnucash.android.ui.transaction.TransactionDetailActivity
import org.gnucash.android.ui.transaction.dialog.BulkMoveDialogFragment
import org.gnucash.android.util.BackupManager.backupActiveBookAsync
import timber.log.Timber

class SearchResultsFragment : MenuFragment(), SearchResultCallback, FragmentResultListener {
    private val viewModel by viewModels<SearchResultsViewModel>()
    private var binding: FragmentTransactionsListBinding? = null
    private var transactionsAdapter: SearchResultsAdapter? = null
    private var isDoubleEntry = true

    /** The WHERE fragment the screen was opened with (kept so a tag chip filter
     *  can be layered on top of it without losing the original query). */
    private var baseWhere: String? = null

    /** Contextual action bar shown while a selection exists. */
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()

        baseWhere = requireArguments().getString(EXTRA_FORM)
        viewModel.where = baseWhere
        isDoubleEntry = isDoubleEntryEnabled(context)

        transactionsAdapter = SearchResultsAdapter(null, isDoubleEntry, this, ::filterByTag)
        // The long press (and "select all") both land here, so the action bar is
        // started from one place instead of at each entry point.
        transactionsAdapter?.onSelectionChanged = {
            val adapter = transactionsAdapter
            if (adapter != null && adapter.isSelectionMode) {
                if (actionMode == null) {
                    actionMode = (requireActivity() as? AppCompatActivity)
                        ?.startSupportActionMode(selectionCallback)
                }
                updateSelectionTitle()
            }
        }
        lifecycleScope.launch {
            viewModel.results.collect { cursor ->
                transactionsAdapter?.changeCursor(cursor)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentTransactionsListBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val actionBar: ActionBar? = this.actionBar
        actionBar?.setTitle(R.string.title_search)

        val binding = this.binding!!
        val context = binding.list.context

        binding.list.setHasFixedSize(true)
        if (context.isLandscape) {
            binding.list.setLayoutManager(GridLayoutManager(context, 2))
        } else {
            binding.list.setLayoutManager(LinearLayoutManager(context))
        }
        binding.list.emptyView = binding.empty
        binding.list.adapter = transactionsAdapter
    }

    override fun invoke(transaction: Transaction, action: SearchResultAction) {
        when (action) {
            SearchResultAction.Delete -> delete(transaction)
            SearchResultAction.Duplicate -> duplicate(transaction)
            SearchResultAction.Edit -> edit(requireContext(), transaction)
            SearchResultAction.Move -> move(transaction)
            SearchResultAction.View -> view(requireContext(), transaction)
        }
    }

    private fun delete(transaction: Transaction) {
        val context: Context = requireContext()
        if (shouldBackupTransactions(context)) {
            backupActiveBookAsync(activity) { result ->
                deleteImpl(context, transaction)
            }
        } else {
            deleteImpl(context, transaction)
        }
    }

    private fun deleteImpl(context: Context, transaction: Transaction) {
        viewModel.delete(transaction)
        updateAllWidgets(context)
    }

    private fun duplicate(transaction: Transaction) {
        viewModel.duplicate(transaction)
    }

    private fun edit(context: Context, transaction: Transaction) {
        val accountUID = transaction.getDefaultAccountUID(TransactionType.CREDIT)
        if (accountUID.isNullOrEmpty()) {
            Timber.w("Account UID required")
            return
        }
        val transactionUID = transaction.uid
        if (transactionUID.isEmpty()) {
            Timber.w("Transaction UID required")
            return
        }
        val intent = Intent(context, FormActivity::class.java)
            .putExtra(UxArgument.FORM_TYPE, FormActivity.FormType.TRANSACTION.name)
            .putExtra(UxArgument.SELECTED_TRANSACTION_UID, transactionUID)
            .putExtra(UxArgument.SELECTED_ACCOUNT_UID, accountUID)
        startActivity(intent)
    }

    private fun move(transaction: Transaction) {
        val accountUID = transaction.getDefaultAccountUID(TransactionType.CREDIT) ?: return
        val uids = arrayOf(transaction.uid)
        val fm = parentFragmentManager
        fm.setFragmentResultListener(BulkMoveDialogFragment.TAG, viewLifecycleOwner, this)
        val fragment = BulkMoveDialogFragment.newInstance(uids, accountUID)
        fragment.show(fm, BulkMoveDialogFragment.TAG)
    }

    /**
     * Narrows the current results to the transactions carrying [tag].
     *
     * The tag predicate is built by [SearchQueryBuilder] (the single place that
     * knows how to express a tag in SQL) and ANDed onto the original WHERE
     * fragment, so a chip tap can never lose or re-bind the query it was
     * opened with. Tapping the same tag again is a no-op.
     */
    private fun filterByTag(tag: String) {
        val tagWhere = SearchQueryBuilder.tagWhere(tag, ComparisonType.Any)
        if (tagWhere.isEmpty()) return

        val combined = SearchQueryBuilder.andWhere(baseWhere, tagWhere)
        if (combined == viewModel.where) return

        viewModel.where = combined
        refresh()
        Toast.makeText(
            requireContext(),
            getString(R.string.search_filtered_by_tag, tag),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun view(context: Context, transaction: Transaction) {
        val accountUID = transaction.getDefaultAccountUID(TransactionType.CREDIT) ?: return
        val transactionUID = transaction.uid
        if (transactionUID.isEmpty()) {
            Timber.w("Transaction UID required")
            return
        }
        val intent = Intent(context, TransactionDetailActivity::class.java)
            .putExtra(UxArgument.SELECTED_TRANSACTION_UID, transactionUID)
            .putExtra(UxArgument.SELECTED_ACCOUNT_UID, accountUID)
        startActivity(intent)
    }

    override fun onFragmentResult(requestKey: String, result: Bundle) {
        if (BulkMoveDialogFragment.TAG == requestKey) {
            val refresh = result.getBoolean(Refreshable.EXTRA_REFRESH)
            if (refresh) refresh()
        } else if (BulkEditTransactionsDialogFragment.TAG == requestKey) {
            val field = BulkEditTransactionsDialogFragment.fieldOf(result)
            if (field != null) {
                applyBulkEdit(
                    field,
                    result.getString(BulkEditTransactionsDialogFragment.EXTRA_VALUE),
                    result.getBoolean(BulkEditTransactionsDialogFragment.EXTRA_TAG_REMOVE),
                    BulkEditTransactionsDialogFragment.uidsOf(result)
                )
            } else {
                // Never leave the user staring at an unchanged list with no message.
                Toast.makeText(
                    requireContext(), R.string.bulk_edit_nothing_updated, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------ bulk

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_search_bulk, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val count = transactionsAdapter?.selection?.size ?: 0
            // A pending action on zero rows is a trap, so keep both disabled
            // until something is ticked.
            menu.findItem(R.id.menu_bulk_edit)?.isEnabled = count > 0
            menu.findItem(R.id.menu_bulk_delete)?.isEnabled = count > 0
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_bulk_select_all -> {
                    transactionsAdapter?.selectAll()
                    true
                }

                R.id.menu_bulk_edit -> {
                    showBulkEdit()
                    true
                }

                R.id.menu_bulk_delete -> {
                    confirmBulkDelete()
                    true
                }

                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            transactionsAdapter?.stopSelection()
        }
    }

    /**
     * Enters multi-select without a long press. The long press alone is
     * undiscoverable, and it is also the only way in for anyone driving the app
     * without a pointer (tests, accessibility).
     */
    private fun startSelectionMode() {
        val adapter = transactionsAdapter ?: return
        if (!adapter.isSelectionMode) adapter.startSelection(null)
        if (actionMode == null) {
            actionMode = (requireActivity() as? AppCompatActivity)
                ?.startSupportActionMode(selectionCallback)
        }
        updateSelectionTitle()
    }

    private fun updateSelectionTitle() {
        val mode = actionMode ?: return
        val count = transactionsAdapter?.selection?.size ?: 0
        mode.title = getString(R.string.bulk_selection_title, count)
        mode.invalidate()
    }

    private fun selectedUids(): List<String> = transactionsAdapter?.selection?.snapshot().orEmpty()

    private fun showBulkEdit() {
        val uids = selectedUids()
        if (uids.isEmpty()) {
            Toast.makeText(requireContext(), R.string.bulk_edit_nothing_selected, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val fm = parentFragmentManager
        fm.setFragmentResultListener(
            BulkEditTransactionsDialogFragment.TAG, viewLifecycleOwner, this
        )
        BulkEditTransactionsDialogFragment.newInstance(uids)
            .show(fm, BulkEditTransactionsDialogFragment.TAG)
    }

    private fun applyBulkEdit(
        field: BulkEditField,
        value: String?,
        tagRemove: Boolean,
        resultUids: List<String>?
    ) {
        // Prefer the uids the dialog was opened for. Falling back to the adapter's live
        // selection is only safe while the action bar is still up — once it is gone the
        // selection is cleared with it and the batch would silently write nothing.
        val uids = resultUids ?: selectedUids()
        if (uids.isEmpty()) return
        // Close the action bar first: the list stays usable while the batch runs,
        // and the count that arrives later is the truth about what was written.
        finishSelection()
        // A wide selection takes a moment even in one transaction; say so up front so
        // "保存" never looks like it did nothing at all.
        Toast.makeText(
            requireContext(), getString(R.string.bulk_edit_running, uids.size), Toast.LENGTH_SHORT
        ).show()
        viewModel.bulkUpdate(uids, field, value, tagRemove) { updated ->
            if (!isAdded) return@bulkUpdate
            val message = if (updated == 0) {
                getString(R.string.bulk_edit_nothing_updated)
            } else {
                getString(R.string.bulk_edit_done, updated)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmBulkDelete() {
        val uids = selectedUids()
        if (uids.isEmpty()) {
            Toast.makeText(requireContext(), R.string.bulk_edit_nothing_selected, Toast.LENGTH_SHORT)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bulk_delete)
            .setMessage(getString(R.string.bulk_delete_confirm, uids.size))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.bulk_delete) { _, _ -> deleteSelected(uids) }
            .show()
    }

    private fun deleteSelected(uids: List<String>) {
        val context = requireContext()
        if (shouldBackupTransactions(context)) {
            backupActiveBookAsync(activity) { deleteSelectedImpl(context, uids) }
        } else {
            deleteSelectedImpl(context, uids)
        }
    }

    private fun deleteSelectedImpl(context: Context, uids: List<String>) {
        finishSelection()
        viewModel.deleteTransactions(uids) { deleted ->
            updateAllWidgets(context)
            Toast.makeText(
                context, getString(R.string.bulk_delete_done, deleted), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun finishSelection() {
        actionMode?.finish()
        actionMode = null
        transactionsAdapter?.stopSelection()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        viewModel.search()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.search_results_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(viewModel.sortOrder.menuId())?.isChecked = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val sortOrder = when (item.itemId) {
            R.id.menu_search_sort_date_desc -> SearchSortOrder.DateDesc
            R.id.menu_search_sort_date_asc -> SearchSortOrder.DateAsc
            R.id.menu_search_sort_amount_desc -> SearchSortOrder.AmountDesc
            R.id.menu_search_sort_amount_asc -> SearchSortOrder.AmountAsc
            else -> null
        }
        if (sortOrder != null) {
            applySort(sortOrder)
            return true
        }
        return when (item.itemId) {
            R.id.menu_search_select -> {
                startSelectionMode()
                true
            }

            R.id.menu_search_summary -> {
                SearchSummaryDialogFragment().show(childFragmentManager, SearchSummaryDialogFragment.TAG)
                true
            }

            R.id.menu_report_search_results -> {
                // Hand over the live query rather than re-deriving one from the widgets:
                // the report has to cover exactly the rows that are on screen.
                ReportsActivity.showSearchResult(requireContext(), viewModel.where)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Re-runs the query with a new ORDER BY (the ViewModel owns the choice, so
     * it survives configuration changes). Selecting the current order is a
     * no-op rather than a useless reload.
     */
    private fun applySort(sortOrder: SearchSortOrder) {
        if (viewModel.sortOrder == sortOrder) return
        viewModel.sortOrder = sortOrder
        requireActivity().invalidateOptionsMenu()
        refresh()
        Toast.makeText(
            requireContext(),
            getString(R.string.search_sort_applied, getString(sortOrder.titleRes())),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun SearchSortOrder.menuId(): Int = when (this) {
        SearchSortOrder.DateDesc -> R.id.menu_search_sort_date_desc
        SearchSortOrder.DateAsc -> R.id.menu_search_sort_date_asc
        SearchSortOrder.AmountDesc -> R.id.menu_search_sort_amount_desc
        SearchSortOrder.AmountAsc -> R.id.menu_search_sort_amount_asc
    }

    private fun SearchSortOrder.titleRes(): Int = when (this) {
        SearchSortOrder.DateDesc -> R.string.search_sort_date_desc
        SearchSortOrder.DateAsc -> R.string.search_sort_date_asc
        SearchSortOrder.AmountDesc -> R.string.search_sort_amount_desc
        SearchSortOrder.AmountAsc -> R.string.search_sort_amount_asc
    }

    companion object {
        const val EXTRA_FORM = "search_where"
    }
}