# -*- coding: utf-8 -*-
"""R25-A：批量修改/删除写入可靠性 + 性能 + 反馈。"""
import sys

sys.path.insert(0, ".")
from _r25_apply import patch  # noqa: E402

W = "app/src/main/java/org/gnucash/android/ui/search/SearchBulkWriter.kt"
D = "app/src/main/java/org/gnucash/android/ui/search/BulkEditTransactionsDialogFragment.kt"
F = "app/src/main/java/org/gnucash/android/ui/search/SearchResultsFragment.kt"

# ---------------------------------------------------------------- SearchBulkWriter
OLD_DELETE = '''    fun delete(uids: Collection<String>): Int {
        var deleted = 0
        for (uid in uids) {
            try {
                if (transactionsDbAdapter.deleteRecord(uid)) deleted++
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete transaction %s", uid)
            }
        }
        return deleted
    }
'''
NEW_DELETE = '''    fun delete(uids: Collection<String>): Int {
        if (uids.isEmpty()) return 0
        // One transaction for the whole batch: a per-row commit is a per-row fsync, so
        // a wide "全选 → 删除" crawled for minutes with nothing on screen moving.
        // Failures are still skipped per row instead of aborting the batch.
        var deleted = 0
        transactionsDbAdapter.beginTransaction()
        try {
            for (uid in uids) {
                try {
                    if (transactionsDbAdapter.deleteRecord(uid)) deleted++
                } catch (e: Exception) {
                    Timber.e(e, "Failed to delete transaction %s", uid)
                }
            }
            transactionsDbAdapter.setTransactionSuccessful()
        } finally {
            transactionsDbAdapter.endTransaction()
        }
        return deleted
    }
'''

OLD_UPDATE_TAIL = '''        var updated = 0
        for (uid in uids) {
            try {
                val transaction = transactionsDbAdapter.getRecordOrNull(uid) ?: continue
                when (field) {
                    BulkEditField.Description ->
                        transaction.description = BulkEdit.normalizeText(rawValue)

                    BulkEditField.Notes ->
                        transaction.notes = BulkEdit.normalizeText(rawValue)

                    BulkEditField.Number ->
                        transaction.number = BulkEdit.normalizeText(rawValue)

                    BulkEditField.Date -> {
                        // An unparsable date must SKIP this transaction, not save it
                        // unchanged and count it as an update — otherwise the user is
                        // told "40 updated" while the dates are all still the old ones.
                        val datePosted = BulkEdit.applyDate(transaction.datePosted, rawValue, zone)
                            ?: continue
                        transaction.datePosted = datePosted
                    }

                    BulkEditField.Tag -> {
                        // Tags are text first and a mirror second: rewrite the
                        // notes, then let addRecord re-derive the mirror.
                        transaction.notes = if (tagRemove) {
                            BulkEdit.removeTag(transaction.notes, tag!!)
                        } else {
                            BulkEdit.addTag(transaction.notes, tag!!)
                        }
                        if (tagRemove) {
                            // A token left in the description would be extracted
                            // straight back into the mirror on the next save.
                            transaction.description =
                                BulkEdit.removeTag(transaction.description, tag!!)
                        }
                    }
                }
                transactionsDbAdapter.addRecord(transaction, DatabaseAdapter.UpdateMethod.Update)
                updated++
            } catch (e: Exception) {
                Timber.e(e, "Failed to bulk-edit transaction %s", uid)
            }
        }
        return updated
    }
}
'''

NEW_UPDATE_TAIL = '''        if (uids.isEmpty()) return 0
        // The whole batch goes in ONE transaction. `addRecord` opens (and therefore
        // commits) a transaction of its own per row, and 2000 committed rows is 2000
        // fsyncs: that is what made "全选 → 改值 → 保存" crawl for minutes after the
        // write had already been moved off the main thread — the screen stayed
        // responsive but the data never seemed to arrive. A row that throws is still
        // logged and skipped rather than aborting the batch.
        var updated = 0
        transactionsDbAdapter.beginTransaction()
        try {
            for (uid in uids) {
                try {
                    val transaction = transactionsDbAdapter.getRecordOrNull(uid) ?: continue
                    when (field) {
                        BulkEditField.Description ->
                            transaction.description = BulkEdit.normalizeText(rawValue)

                        BulkEditField.Notes ->
                            transaction.notes = BulkEdit.normalizeText(rawValue)

                        BulkEditField.Number ->
                            transaction.number = BulkEdit.normalizeText(rawValue)

                        BulkEditField.Date -> {
                            // An unparsable date must SKIP this transaction, not save it
                            // unchanged and count it as an update — otherwise the user is
                            // told "40 updated" while the dates are all still the old ones.
                            val datePosted = BulkEdit.applyDate(
                                transaction.datePosted, rawValue, zone
                            ) ?: continue
                            transaction.datePosted = datePosted
                        }

                        BulkEditField.Tag -> {
                            // Tags are text first and a mirror second: rewrite the
                            // notes, then let addRecord re-derive the mirror.
                            transaction.notes = if (tagRemove) {
                                BulkEdit.removeTag(transaction.notes, tag!!)
                            } else {
                                BulkEdit.addTag(transaction.notes, tag!!)
                            }
                            if (tagRemove) {
                                // A token left in the description would be extracted
                                // straight back into the mirror on the next save.
                                transaction.description =
                                    BulkEdit.removeTag(transaction.description, tag!!)
                            }
                        }
                    }
                    transactionsDbAdapter.addRecord(
                        transaction, DatabaseAdapter.UpdateMethod.Update
                    )
                    updated++
                } catch (e: Exception) {
                    Timber.e(e, "Failed to bulk-edit transaction %s", uid)
                }
            }
            transactionsDbAdapter.setTransactionSuccessful()
        } finally {
            transactionsDbAdapter.endTransaction()
        }
        return updated
    }
}
'''

patch(W, [(OLD_DELETE, NEW_DELETE), (OLD_UPDATE_TAIL, NEW_UPDATE_TAIL)])

# ------------------------------------------------- BulkEditTransactionsDialogFragment
OLD_D = '''            .setPositiveButton(R.string.btn_save) { _, _ ->
                val position = binding.inputBulkField.selectedItemPosition
                val field = fields.getOrNull(position) ?: return@setPositiveButton
                val result = Bundle()
                result.putString(EXTRA_FIELD, field.name)
                result.putString(EXTRA_VALUE, binding.inputBulkValue.text?.toString())
                result.putBoolean(EXTRA_TAG_REMOVE, binding.inputBulkTagRemove.isChecked)
                parentFragmentManager.setFragmentResult(TAG, result)
            }
'''
NEW_D = '''            .setPositiveButton(R.string.btn_save) { _, _ ->
                // `selectedItemPosition` is INVALID_POSITION until the spinner has laid
                // out; returning without a result in that window would close the dialog
                // and write nothing at all, which is exactly the "保存后卡住、没写入"
                // report. Fall back to the entry the spinner is showing anyway.
                val position = binding.inputBulkField.selectedItemPosition
                val field = fields.getOrNull(position) ?: fields.first()
                val result = Bundle()
                result.putString(EXTRA_FIELD, field.name)
                result.putString(EXTRA_VALUE, binding.inputBulkValue.text?.toString())
                result.putBoolean(EXTRA_TAG_REMOVE, binding.inputBulkTagRemove.isChecked)
                // The host must act on *these* rows: re-reading its own live selection
                // after the dialog closes is how the batch can end up empty.
                result.putStringArray(EXTRA_UIDS, uids.toTypedArray())
                parentFragmentManager.setFragmentResult(TAG, result)
            }
'''

OLD_C = '''        const val EXTRA_TAG_REMOVE: String = "bulk_edit_tag_remove"
'''
NEW_C = '''        const val EXTRA_TAG_REMOVE: String = "bulk_edit_tag_remove"

        /** UIDs the dialog was opened for, echoed back so the host never re-reads a
         *  selection that may already have been cleared by the closing action bar. */
        const val EXTRA_UIDS: String = "bulk_edit_uids"

        /** @return the uids carried by the result, or `null` when there are none. */
        fun uidsOf(result: Bundle): List<String>? =
            result.getStringArray(EXTRA_UIDS)?.toList()?.takeIf { it.isNotEmpty() }
'''

patch(D, [(OLD_D, NEW_D), (OLD_C, NEW_C)])

# ------------------------------------------------------------ SearchResultsFragment
OLD_R1 = '''            val field = BulkEditTransactionsDialogFragment.fieldOf(result)
            if (field != null) {
                applyBulkEdit(
                    field,
                    result.getString(BulkEditTransactionsDialogFragment.EXTRA_VALUE),
                    result.getBoolean(BulkEditTransactionsDialogFragment.EXTRA_TAG_REMOVE)
                )
            }
'''
NEW_R1 = '''            val field = BulkEditTransactionsDialogFragment.fieldOf(result)
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
'''

OLD_R2 = '''    private fun applyBulkEdit(field: BulkEditField, value: String?, tagRemove: Boolean) {
        // Snapshot before leaving selection mode: the write is asynchronous now and
        // the adapter's selection is cleared the moment the action bar closes.
        val uids = selectedUids()
        if (uids.isEmpty()) return
        // Close the action bar first: the list stays usable while the batch runs,
        // and the count that arrives later is the truth about what was written.
        finishSelection()
        viewModel.bulkUpdate(uids, field, value, tagRemove) { updated ->
'''
NEW_R2 = '''    private fun applyBulkEdit(
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
'''

patch(F, [(OLD_R1, NEW_R1), (OLD_R2, NEW_R2)])
print("R25-A done")
