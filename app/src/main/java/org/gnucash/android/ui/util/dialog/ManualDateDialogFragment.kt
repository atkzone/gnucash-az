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
import org.gnucash.android.databinding.DialogManualDateBinding
import org.gnucash.android.util.ManualDate

/**
 * 手工填写 a date as `20260129`.
 *
 * Reached from every `DatePickerDialogFragment` in the app through its 「手工填写」 button,
 * so it is the one place a date can be typed instead of scrolled to. The field opens on
 * the date the calendar was showing — "显示当前时间" — and the digits are normalised as
 * they arrive, so whatever the user pastes ends up as the same eight characters the rest
 * of the app (and the parser) expects.
 *
 * Single tap puts the caret in the field, double tap selects all of it: on an eight
 * character box those two gestures are the whole interaction, and they replace the
 * fiddly "tap exactly between two digits" the raw field would demand.
 */
class ManualDateDialogFragment : VolatileDialogFragment() {

    private var _binding: DialogManualDateBinding? = null
    private val binding get() = _binding!!

    /** Called with the start of the typed day. Never called with an unparsable value. */
    private var onDateEntered: ((Long) -> Unit)? = null

    private var initialMillis: Long = System.currentTimeMillis()

    /** Guards the watcher while it rewrites the very field it is watching. */
    private var normalising = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogManualDateBinding.inflate(layoutInflater)
        val edit = binding.inputManualDate
        edit.setText(ManualDate.format(initialMillis))
        edit.setSelection(edit.text?.length ?: 0)
        attachTapBehaviour(edit)
        edit.addTextChangedListener(normaliser)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_manual_date)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save, null)
            .create()

        // Wired in onShow so a bad date can keep the dialog open with the digits intact.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSaveClicked() }
        }
        // The keyboard is the point of this dialog: ask for it instead of waiting for a tap.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        edit.requestFocus()
        return dialog
    }

    private val normaliser = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (normalising) return
            val typed = s?.toString().orEmpty()
            val digits = ManualDate.digitsOf(typed)
            if (digits == typed) return
            normalising = true
            binding.inputManualDate.setText(digits)
            binding.inputManualDate.setSelection(digits.length)
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
        // Consuming the touch means the field no longer places the caret itself; for an
        // eight digit box the two gestures above are all it needs.
        edit.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun showKeyboard(edit: EditText) {
        val manager = edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun onSaveClicked() {
        val context = context ?: return
        val millis = ManualDate.parse(binding.inputManualDate.getText()?.toString())
        if (millis == null) {
            Toast.makeText(context, R.string.toast_manual_date_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        onDateEntered?.invoke(millis)
        dismiss()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG: String = "manual_date"

        /**
         * @param millis date the calendar was showing, i.e. what the field opens on.
         * @param onDateEntered receives the start of the day the user typed.
         */
        fun newInstance(millis: Long, onDateEntered: (Long) -> Unit): ManualDateDialogFragment =
            ManualDateDialogFragment().apply {
                initialMillis = millis
                this.onDateEntered = onDateEntered
            }
    }
}
