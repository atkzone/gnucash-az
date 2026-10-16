package org.gnucash.android.ui.search

import android.database.Cursor
import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.TagsDbAdapter
import org.gnucash.android.db.adapter.TransactionsDbAdapter
import org.gnucash.android.model.Transaction
import org.gnucash.android.model.TransactionType
import timber.log.Timber
import java.math.BigDecimal

class SearchResultsViewModel : ViewModel() {
    var where: String? = null

    /** Current ordering of the result list; changing it re-runs the query. */
    var sortOrder: SearchSortOrder = SearchSortOrder.DateDesc

    private val _results = MutableStateFlow<Cursor?>(null)
    val results: StateFlow<Cursor?> = _results

    /** Totals / category breakdown of the current result set (see [SearchSummary]). */
    private val _summary = MutableStateFlow(SearchSummary(0, emptyList(), emptyList(), emptyList()))
    val summary: StateFlow<SearchSummary> = _summary

    private val transactionsDbAdapter: TransactionsDbAdapter = TransactionsDbAdapter.instance
    private val accountsDbAdapter: AccountsDbAdapter = AccountsDbAdapter.instance
    private val tagsDbAdapter: TagsDbAdapter = TagsDbAdapter(accountsDbAdapter.holder)

    /** Bulk writes (delete / one-field edit) all go through here. */
    private val bulkWriter = SearchBulkWriter()

    /**
     * Bulk writes run off the main thread: one `addRecord` per ticked row, and
     * "全选" on a wide query is thousands of them. Left inline this blocked the UI
     * for the whole batch, which is what made 保存 look like a freeze.
     */
    private val bulkRunner by lazy { BulkActionRunner(viewModelScope) }

    fun search() {
        viewModelScope.launch(Dispatchers.IO) {
            val cursorOld = _results.value
            withContext(Dispatchers.Main) {
                cursorOld?.close()
            }

            val where = this@SearchResultsViewModel.where ?: return@launch
            Timber.d("Search transactions: $where")

            val order = sortOrder
            val cursor = if (order == SearchSortOrder.DateDesc) {
                transactionsDbAdapter.fetchSearch(where)
            } else {
                transactionsDbAdapter.fetchSearch(where, order.orderBy())
            }

            // The aggregate needs per-transaction amounts and tags, which live in
            // the model layer — walk the cursor once, on this (IO) thread.
            val transactions = readTransactions(cursor)

            // `toSearchResultRow` resolves the account name and the tags, and both are
            // database reads — one pair per matched row. Doing that inside the `Main`
            // block below put thousands of queries on the main thread right after a bulk
            // write, which is what turned "保存" into an ANR. They run here, on the IO
            // thread, and the main thread only assigns the finished list.
            val summaryRows = transactions.map { it.toSearchResultRow() }

            withContext(Dispatchers.Main) {
                _summary.value = SearchSummary.of(summaryRows)
                _results.emit(cursor)
            }
        }
    }

    /**
     * Materialises the whole cursor, then rewinds it so the adapter's
     * `changeCursor` still starts from the top.
     */
    private fun readTransactions(cursor: Cursor?): List<Transaction> {
        if (cursor == null) return emptyList()
        val transactions = ArrayList<Transaction>(cursor.count)
        try {
            cursor.moveToPosition(-1)
            while (cursor.moveToNext()) {
                transactions.add(transactionsDbAdapter.buildModelInstance(cursor))
            }
            cursor.moveToPosition(-1)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read search results")
        }
        return transactions
    }

    private fun Transaction.toSearchResultRow(): SearchResultRow {
        // Debit-side magnitude — the same definition SearchSortOrder orders by.
        val amount = splits
            .filter { it.type == TransactionType.DEBIT }
            .fold(BigDecimal.ZERO) { acc, split -> acc + split.value.toBigDecimal() }

        val accountUID = defaultAccountUID
        val accountName = if (accountUID.isNullOrEmpty()) {
            ""
        } else {
            runCatching { accountsDbAdapter.getFullyQualifiedAccountName(accountUID) }
                .getOrNull().orEmpty()
        }
        val tags = runCatching { tagsDbAdapter.getTagNamesForTransaction(uid) }
            .getOrDefault(emptyList())

        return SearchResultRow(amount, commodity.currencyCode, accountName, tags)
    }

    fun delete(transaction: Transaction) {
        try {
            transactionsDbAdapter.deleteRecord(transaction)
            search()
        } catch (e: SQLException) {
            Timber.e(e)
        }
    }

    /**
     * Deletes every uid off the main thread and re-runs the query; see
     * [SearchBulkWriter.delete].
     *
     * @param onDone number of rows actually deleted, delivered on the main thread.
     */
    fun deleteTransactions(uids: Collection<String>, onDone: (Int) -> Unit) {
        bulkRunner.run({ bulkWriter.delete(uids) }) { deleted ->
            search()
            onDone(deleted)
        }
    }

    /**
     * Writes one field on N transactions, then re-runs the query.
     *
     * The write itself lives in [SearchBulkWriter]: it is synchronous and goes
     * through the model, which is what keeps the tag mirror in step with the
     * `#tag` text. All this adds is the refresh.
     */
    fun bulkUpdate(
        uids: Collection<String>,
        field: BulkEditField,
        rawValue: String?,
        tagRemove: Boolean,
        onDone: (Int) -> Unit
    ) {
        bulkRunner.run({ bulkWriter.update(uids, field, rawValue, tagRemove) }) { updated ->
            search()
            onDone(updated)
        }
    }

    fun duplicate(transaction: Transaction) {
        try {
            val duplicate = transaction.copy(datePosted = System.currentTimeMillis())
            transactionsDbAdapter.insert(duplicate)
            search()
        } catch (e: SQLException) {
            Timber.e(e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            _results.value?.close()
            _results.value = null
        } catch (_: Exception) {
        }
    }
}
