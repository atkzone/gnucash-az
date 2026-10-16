package org.gnucash.android.ui.util.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.gnucash.android.R
import org.gnucash.android.databinding.DialogManualDateRangeBinding
import org.gnucash.android.util.ManualDate
import org.gnucash.android.util.ManualDateRange

/**
 * 手工填写 a **date range** as two `20260129` fields.
 *
 * The report screen's calendar is a range control, so its 「手工填写」 has to name two days.
 * Both fields behave exactly like the single-date one — normalised as they are typed, single
 * tap to put the caret at the end, double tap to select all — and the pair is validated by
 * [ManualDateRange], so an unparsable day or a reversed order keeps the dialog open instead
 * of writing a period that would draw an empty report.
 */
class ManualDateRangeDialogFragment : VolatileDialogFragment() {

    private var _binding: DialogManualDateRangeBinding? = null
    private val binding get() = _binding!!

    /** Called with the two day-starts. Never called with unparsable or reversed dates. */
    private var onRangeEntered: ((Long, Long) -> Unit)? = null

    private var initialStart: Long = System.currentTimeMillis()
    private var initialEnd: Long = System.currentTimeMillis()

    /** Guards the watcher while it rewrites the very field it is watching. */
    private var normalising = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogManualDateRangeBinding.inflate(layoutInflater)
        seed(binding.inputManualDateFrom, initialStart)
        seed(binding.inputManualDateTo, initialEnd)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_manual_date_range)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save, null)
            .create()

        // Wired in onShow so a bad pair can keep the dialog open with the digits intact.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSaveClicked() }
        }
        // The keyboard is the point of this dialog: ask for it instead of waiting for a tap.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        binding.inputManualDateFrom.requestFocus()
        return dialog
    }

    private fun seed(edit: EditText, millis: Long) {
        edit.setText(ManualDate.format(millis))
        edit.setSelection(edit.text?.length ?: 0)
        attachTapBehaviour(edit)
        edit.addTextChangedListener(normaliser(edit))
    }

    private fun normaliser(target: EditText) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (normalising) return
            val typed = s?.toString().orEmpty()
            val digits = ManualDate.digitsOf(typed)
            if (digits == typed) return
            normalising = true
            target.setText(digits)
            target.setSelection(digits.length)
            normalising = false
        }
    }

    private fun attachTapBehaviour(edit: EditText) {
        val detector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    edit.requestFocus()
                    edit.setSelection(edit.text?.length ?: 0)
                    showKeyboard(edit)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    edit.requestFocus()
                    edit.selectAll()
                    showKeyboard(edit)
                    return true
                }
            }
        )
        edit.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun showKeyboard(edit: EditText) {
        val manager = edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun onSaveClicked() {
        val context = context ?: return
        val range = ManualDateRange.parse(
            binding.inputManualDateFrom.getText()?.toString(),
            binding.inputManualDateTo.getText()?.toString()
        )
        if (range == null) {
            Toast.makeText(context, R.string.toast_manual_date_range_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        onRangeEntered?.invoke(range.first, range.second)
        dismiss()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG: String = "manual_date_range"

        /**
         * @param startMillis / [endMillis] what the calendar was showing, i.e. what the two
         *        fields open on.
         * @param onRangeEntered receives the start of each typed day.
         */
        fun newInstance(
            startMillis: Long,
            endMillis: Long,
            onRangeEntered: (Long, Long) -> Unit
        ): ManualDateRangeDialogFragment = ManualDateRangeDialogFragment().apply {
            initialStart = startMillis
            initialEnd = endMillis
            this.onRangeEntered = onRangeEntered
        }
    }
}
