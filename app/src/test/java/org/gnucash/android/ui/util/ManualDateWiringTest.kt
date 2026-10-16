package org.gnucash.android.ui.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * The 手工填写 entry, pinned at the source shape.
 *
 * Nothing in the app decides "which screens get the manual field": the whole point is
 * that the one calendar dialog every date already opens grows a third button. That is
 * only true while every date really does go through [DatePickerDialogFragment], and a
 * unit test of [org.gnucash.android.util.ManualDate] cannot see whether the button,
 * the gesture handlers or the auto-conversion were ever wired up.
 */
class ManualDateWiringTest {

    private fun sourceText(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            for (candidate in candidates) {
                val file = File(dir, candidate.path)
                if (file.isFile) return file.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the calendar grows a manual-entry button`() {
        val picker = sourceText(PICKER)
        assertThat(picker)
            .`as`("中性按钮就是手工填写的入口")
            .contains("DialogInterface.BUTTON_NEUTRAL")
        assertThat(picker).contains("R.string.btn_manual_date")
        assertThat(picker)
            .`as`("按钮必须后绑 listener，否则 AlertDialog 自带的中性处理会先把日历关掉")
            .contains("null as DialogInterface.OnClickListener?")
            .contains(".setOnClickListener { showManualEntry() }")
    }

    @Test
    fun `a typed date is reported through the same listener the calendar uses`() {
        val picker = sourceText(PICKER)
        assertThat(picker)
            .`as`("同一个回调 = 所有调用方都不需要知道日期是点出来的还是敲进去的")
            .contains("applyManualDate(millis)")
            .contains("listener?.onDateSet(")
    }

    @Test
    fun `the entry dialog shows 20260129 and converts as you type`() {
        val dialog = sourceText(DIALOG)
        assertThat(dialog).`as`("初始值按 20260129 展示").contains("ManualDate.format(")
        assertThat(dialog)
            .`as`("输入过程中归一化成八位数字")
            .contains("ManualDate.digitsOf(")
        assertThat(dialog)
            .`as`("保存时严格解析，非法日期拒绝而不是滚动到下个月")
            .contains("ManualDate.parse(")
        assertThat(dialog).contains("R.string.toast_manual_date_invalid")
        assertThat(dialog)
            .`as`("程序化回写要防回环，否则每次输入都会重排光标")
            .contains("normalising")
    }

    @Test
    fun `single tap edits and double tap selects everything`() {
        val dialog = sourceText(DIALOG)
        assertThat(dialog).contains("GestureDetector(")
        assertThat(dialog).contains("onSingleTapConfirmed")
        assertThat(dialog).contains("onDoubleTap")
        assertThat(dialog).contains("selectAll()")
    }

    @Test
    fun `the input layout ships the 20260129 example`() {
        val layout = sourceText(LAYOUT)
        assertThat(layout).`as`("占位示例就是格式说明").contains("20260129")
        assertThat(layout).contains("@+id/input_manual_date")
        assertThat(layout).contains("android:maxLength=\"8\"")
    }

    @Test
    fun `the report range calendar offers the same entry`() {
        // The report period is a *range* control, and it is still a calendar — leaving it
        // out would make 手工填写 a "most dates" feature, which is the half-measure this
        // whole change exists to avoid.
        val picker = sourceText(RANGE_PICKER)
        assertThat(picker).contains("R.string.btn_manual_date")
        assertThat(picker)
            .`as`("范围日历是 AlertDialog，中性按钮取 AlertDialog.BUTTON_NEUTRAL")
            .contains("AlertDialog.BUTTON_NEUTRAL")
        assertThat(picker)
            .`as`("与单选日历同形：后绑 listener，别让 AlertDialog 把日历先关掉")
            .contains(".setNeutralButton(R.string.btn_manual_date, null)")
            .contains(".setOnClickListener { showManualEntry() }")
        assertThat(picker)
            .`as`("两个日期经同一个回调回给报表，报表侧不需要第二套接线")
            .contains("ManualDateRangeDialogFragment")
            .contains("dateRangeSetListener?.onDateRangeSet(")
    }

    @Test
    fun `the range dialog validates through the pure rule`() {
        val dialog = sourceText(RANGE_DIALOG)
        assertThat(dialog).contains("DialogManualDateRangeBinding")
        assertThat(dialog)
            .`as`("校验（含「结束不能早于起始」）收在纯函数里，对话框只负责报错")
            .contains("ManualDateRange.parse(")
        assertThat(dialog).contains("R.string.toast_manual_date_range_invalid")
        assertThat(dialog).contains("ManualDate.format(")
        assertThat(dialog).contains("ManualDate.digitsOf(")
    }

    @Test
    fun `the range input layout ships two eight digit fields`() {
        val layout = sourceText(RANGE_LAYOUT)
        assertThat(layout).contains("@+id/input_manual_date_from")
        assertThat(layout).contains("@+id/input_manual_date_to")
        assertThat(layout).contains("@string/label_manual_date_from")
        assertThat(layout).contains("@string/label_manual_date_to")
    }

    companion object {
        private const val PICKER =
            "src/main/java/org/gnucash/android/ui/util/dialog/DatePickerDialogFragment.kt"
        private const val DIALOG =
            "src/main/java/org/gnucash/android/ui/util/dialog/ManualDateDialogFragment.kt"
        private const val LAYOUT = "src/main/res/layout/dialog_manual_date.xml"
        private const val RANGE_PICKER =
            "src/main/java/org/gnucash/android/ui/util/dialog/DateRangePickerDialogFragment.kt"
        private const val RANGE_DIALOG =
            "src/main/java/org/gnucash/android/ui/util/dialog/ManualDateRangeDialogFragment.kt"
        private const val RANGE_LAYOUT = "src/main/res/layout/dialog_manual_date_range.xml"
    }
}
