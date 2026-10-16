# -*- coding: utf-8 -*-
"""R25-C：日期「手工填写」入口 + 设置页标题。"""
import sys

sys.path.insert(0, ".")
from _r25_apply import patch  # noqa: E402

EN = "app/src/main/res/values/strings.xml"
ZH = "app/src/main/res/values-zh-rCN/strings.xml"
PICKER = "app/src/main/java/org/gnucash/android/ui/util/dialog/DatePickerDialogFragment.kt"
ACT = "app/src/main/java/org/gnucash/android/ui/transaction/view/ExtendedViewsActivity.kt"
ACT_LAYOUT_DIR = "app/src/main/java/org/gnucash/android/ui/transaction/view"

# ------------------------------------------------------------------------------- strings
patch(EN, [(
    '''    <string name="hint_manual_date">20260129</string>
''',
    '''    <string name="hint_manual_date">20260129</string>
    <string name="hint_manual_date_format">Eight digits, year first — e.g. 20260129</string>
''')])
patch(ZH, [(
    '''    <string name="hint_manual_date">20260129</string>
''',
    '''    <string name="hint_manual_date">20260129</string>
    <string name="hint_manual_date_format">八位数字，年月日在后 — 例如 20260129</string>
''')])

# --------------------------------------------------------------- DatePickerDialogFragment
OLD_HEAD = '''import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import java.util.Calendar

/**
 * Fragment for displaying a date picker dialog
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
     * Creates and returns an Android [DatePickerDialog]
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return DatePickerDialog(
            requireContext(),
            listener,
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH),
            date.get(Calendar.DAY_OF_MONTH)
        )
    }
'''
NEW_HEAD = '''import android.app.DatePickerDialog
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
'''
patch(PICKER, [(OLD_HEAD, NEW_HEAD)])

# ------------------------------------------------------------------------ ExtendedViewsActivity
OLD_ACT_DOC = ''' * A plain tool-bar screen rather than a drawer one: it is reached from the
 * transaction list's overflow submenu and is a sub-screen of that list, so
 * putting a second navigation drawer under it would offer a second way to the
 * same places. It does extend [PasscodeLockActivity] so the passcode lock and the
 * theme helper still apply here.
'''
NEW_ACT_DOC = ''' * A plain tool-bar screen rather than a drawer one: it is reached from a first-level
 * item of the transaction list's overflow menu and is a sub-screen of that list, so
 * putting a second navigation drawer under it would offer a second way to the
 * same places. It does extend [PasscodeLockActivity] so the passcode lock and the
 * theme helper still apply here.
'''
OLD_ACT_TITLE = '''            setTitle(R.string.title_extended_views)
'''
NEW_ACT_TITLE = '''            // Same wording as the menu entry that opens it, so the screen the user
            // lands on is recognisably the one they tapped.
            setTitle(R.string.menu_extended_view_settings)
'''
patch(ACT, [(OLD_ACT_DOC, NEW_ACT_DOC), (OLD_ACT_TITLE, NEW_ACT_TITLE)])
print("R25-C done")
