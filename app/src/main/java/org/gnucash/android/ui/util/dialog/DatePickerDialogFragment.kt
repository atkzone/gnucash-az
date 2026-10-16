/*
 * Copyright (c) 2012 Ngewi Fet <ngewif@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.ui.util.dialog

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import org.gnucash.android.R
import java.util.Calendar

/**
 * Fragment for displaying a date picker dialog
 *
 * It also offers 「手工填写」: scrolling a calendar to a day months away is the slow path,
 * and the button hands the entry to [ManualDateDialogFragment]. Every date in the app goes
 * through this fragment, so that is one button instead of one per screen.
 *
 * @author Ngewi Fet <ngewif@gmail.com>
 */
class DatePickerDialogFragment : VolatileDialogFragment() {
    /**
     * Listener to notify when the date is set.
     */
    private var listener: DatePickerDialog.OnDateSetListener? = null

    /**
     * Date selected in the dialog or to which the dialog is initialized
     */
    private val date: Calendar = Calendar.getInstance()

    /**
     * The dialog itself, kept so the manual entry can move the calendar and report
     * through the very same listener.
     */
    private var dialog: DatePickerDialog? = null

    /**
     * Creates and returns an Android [DatePickerDialog]
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val picker = DatePickerDialog(
            requireContext(),
            listener,
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH),
            date.get(Calendar.DAY_OF_MONTH)
        )
        dialog = picker
        // Installed with a null listener on purpose: AlertDialog's own neutral handler
        // dismisses the dialog, which would close the calendar before anything is typed.
        picker.setButton(
            DialogInterface.BUTTON_NEUTRAL,
            requireContext().getString(R.string.btn_manual_date),
            null as DialogInterface.OnClickListener?
        )
        picker.setOnShowListener {
            picker.getButton(DialogInterface.BUTTON_NEUTRAL)
                .setOnClickListener { showManualEntry() }
        }
        return picker
    }

    /** Opens the entry dialog on whatever day the calendar is showing right now. */
    private fun showManualEntry() {
        val picker = dialog ?: return
        val current = Calendar.getInstance().apply {
            set(Calendar.YEAR, picker.datePicker.year)
            set(Calendar.MONTH, picker.datePicker.month)
            set(Calendar.DAY_OF_MONTH, picker.datePicker.dayOfMonth)
        }
        ManualDateDialogFragment
            .newInstance(current.timeInMillis) { millis -> applyManualDate(millis) }
            .show(parentFragmentManager, ManualDateDialogFragment.TAG)
    }

    /**
     * Reports a typed date through **the same listener** the calendar uses.
     *
     * That is what makes this one change for every screen: nothing above has to know
     * whether the date came from the calendar or from the keyboard.
     */
    private fun applyManualDate(millis: Long) {
        val picker = dialog ?: return
        val day = Calendar.getInstance().apply { timeInMillis = millis }
        val year = day.get(Calendar.YEAR)
        val month = day.get(Calendar.MONTH)
        val dayOfMonth = day.get(Calendar.DAY_OF_MONTH)
        picker.datePicker.updateDate(year, month, dayOfMonth)
        listener?.onDateSet(picker.datePicker, year, month, dayOfMonth)
        dismiss()
    }

    companion object {
        /**
         * Create a new instance.
         *
         * @param listener   Listener to notify when the date is set and the dialog is closed
         * @param dateMillis Date in milliseconds to which to initialize the dialog
         */
        fun newInstance(
            listener: DatePickerDialog.OnDateSetListener,
            dateMillis: Long
        ): DatePickerDialogFragment {
            val fragment = DatePickerDialogFragment()
            fragment.listener = listener
            if (dateMillis > 0) {
                fragment.date.timeInMillis = dateMillis
            }
            return fragment
        }
    }
}
