package org.gnucash.android.ui.search

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import org.gnucash.android.R
import org.gnucash.android.databinding.DialogBulkEditBinding
import org.gnucash.android.ui.common.UxArgument

/**
 * "Change one value on N transactions" dialog.
 *
 * It does not touch the database itself: it only collects the field and the new
 * value and hands them to the host via the fragment-result API. All the writing
 * (and the tag text/mirror contract that comes with it) stays in the ViewModel,
 * so a cancelled dialog can never leave a half-applied edit.
 */
class BulkEditTransactionsDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogBulkEditBinding.inflate(layoutInflater)
        val context = requireContext()
        val uids = requireArguments().getStringArray(UxArgument.SELECTED_TRANSACTION_UIDS)
            ?.toList().orEmpty()

        val fields = BulkEditField.values().toList()
        val fieldAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            fields.map { context.getString(it.titleRes) }
        )
        fieldAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.inputBulkField.adapter = fieldAdapter
        binding.inputBulkValue.hint = context.getString(fields.first().hintRes)
        binding.inputBulkField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val field = fields.getOrNull(position) ?: return
                binding.inputBulkValue.hint = context.getString(field.hintRes)
                // The "remove instead of add" switch only means something for tags.
                binding.inputBulkTagRemove.isVisible = field.isTag
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        return AlertDialog.Builder(context, theme)
            .setTitle(context.getString(R.string.bulk_edit_title, uids.size))
            .setView(binding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
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
            .create()
    }

    companion object {
        const val TAG: String = "bulk_edit_transactions"

        const val EXTRA_FIELD: String = "bulk_edit_field"
        const val EXTRA_VALUE: String = "bulk_edit_value"
        const val EXTRA_TAG_REMOVE: String = "bulk_edit_tag_remove"

        /** UIDs the dialog was opened for, echoed back so the host never re-reads a
         *  selection that may already have been cleared by the closing action bar. */
        const val EXTRA_UIDS: String = "bulk_edit_uids"

        /** @return the uids carried by the result, or `null` when there are none. */
        fun uidsOf(result: Bundle): List<String>? =
            result.getStringArray(EXTRA_UIDS)?.toList()?.takeIf { it.isNotEmpty() }

        /** @return the field encoded in the result, or `null` when unknown. */
        fun fieldOf(result: Bundle): BulkEditField? {
            val name = result.getString(EXTRA_FIELD) ?: return null
            return BulkEditField.values().firstOrNull { it.name == name }
        }

        fun newInstance(transactionUIDs: Collection<String>): BulkEditTransactionsDialogFragment {
            val args = Bundle()
            args.putStringArray(
                UxArgument.SELECTED_TRANSACTION_UIDS,
                transactionUIDs.toTypedArray()
            )
            return BulkEditTransactionsDialogFragment().apply { arguments = args }
        }
    }
}
