"""R25 需求⑤ 补：报表「日期范围」日历也加「手工填写」。"""
import _r25_apply as P

RANGE = "app/src/main/java/org/gnucash/android/ui/util/dialog/DateRangePickerDialogFragment.kt"
VALUES = "app/src/main/res/values/strings.xml"
ZH = "app/src/main/res/values-zh-rCN/strings.xml"

P.patch(RANGE, [
    (
        """        val context = requireContext()
        return AlertDialog.Builder(context, theme)
            .setTitle(R.string.report_time_range_picker_title)
            .setView(binding.root)
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Dismisses itself
            }
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                onRangeSelected(binding)
            }
            .create()
    }
""",
        """        val context = requireContext()
        val dialog = AlertDialog.Builder(context, theme)
            .setTitle(R.string.report_time_range_picker_title)
            .setView(binding.root)
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Dismisses itself
            }
            .setNeutralButton(R.string.btn_manual_date, null)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                onRangeSelected(binding)
            }
            .create()

        // Installed with a null listener on purpose: AlertDialog's own neutral handler
        // dismisses the dialog, which would close the calendar before anything is typed.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener { showManualEntry() }
        }
        return dialog
    }

    /**
     * Opens the typed-range dialog on whatever period the calendar is showing right now.
     *
     * The report calendar is a *range* control, so its 手工填写 names two days; the typed
     * pair is reported through the very listener the calendar uses, which is what keeps
     * this one change enough for every report.
     */
    private fun showManualEntry() {
        ManualDateRangeDialogFragment
            .newInstance(
                startRange.toDate().time,
                endRange.toDate().time
            ) { start, end ->
                dateRangeSetListener?.onDateRangeSet(LocalDate(start), LocalDate(end))
                dismiss()
            }
            .show(parentFragmentManager, ManualDateRangeDialogFragment.TAG)
    }
""",
    ),
])

NEW_STRINGS = [
    (
        "range",
        """    <string name="title_manual_date_range">Enter date range</string>
    <string name="label_manual_date_from">From</string>
    <string name="label_manual_date_to">To</string>
    <string name="toast_manual_date_range_invalid">Check the two dates — 20260129, and the end cannot come before the start</string>
""",
        """    <string name="title_manual_date_range">手工填写日期范围</string>
    <string name="label_manual_date_from">起始</string>
    <string name="label_manual_date_to">结束</string>
    <string name="toast_manual_date_range_invalid">请检查两个日期（20260129）；结束不能早于起始</string>
""",
    ),
]

P.patch(VALUES, [("\n</resources>", "\n" + NEW_STRINGS[0][1] + "</resources>")])
P.patch(ZH, [("\n</resources>", "\n" + NEW_STRINGS[0][2] + "</resources>")])
