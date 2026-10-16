package org.gnucash.android.ui.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Value handling of the bulk edit dialog.
 *
 * Pure logic (no Robolectric): the dialog only collects text, and everything
 * that could quietly write the wrong value lives here.
 */
class BulkEditTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun `text values are trimmed but an empty value is still a value`() {
        assertThat(BulkEdit.normalizeText("  hello  ")).isEqualTo("hello")
        // "clear this field on 40 transactions" is a legitimate request.
        assertThat(BulkEdit.normalizeText("")).isEmpty()
        assertThat(BulkEdit.normalizeText("   ")).isEmpty()
        assertThat(BulkEdit.normalizeText(null)).isEmpty()
    }

    @Test
    fun `adding a tag appends it to the notes`() {
        assertThat(BulkEdit.addTag("午饭", "餐饮")).isEqualTo("午饭 #餐饮")
        assertThat(BulkEdit.addTag("", "餐饮")).isEqualTo("#餐饮")
        assertThat(BulkEdit.addTag(null, "餐饮")).isEqualTo("#餐饮")
    }

    @Test
    fun `adding a tag is idempotent`() {
        val once = BulkEdit.addTag("午饭 #餐饮", "餐饮")
        assertThat(once).isEqualTo("午饭 #餐饮")
        assertThat(BulkEdit.addTag(once, "餐饮")).isEqualTo(once)
    }

    @Test
    fun `adding a tag accepts a leading hash in the input`() {
        assertThat(BulkEdit.addTag("午饭", "#餐饮")).isEqualTo("午饭 #餐饮")
    }

    @Test
    fun `adding an unusable tag leaves the text untouched`() {
        assertThat(BulkEdit.addTag("午饭", "")).isEqualTo("午饭")
        assertThat(BulkEdit.addTag("午饭", "   ")).isEqualTo("午饭")
        assertThat(BulkEdit.addTag("午饭", "#")).isEqualTo("午饭")
    }

    @Test
    fun `removing a tag keeps the other tags`() {
        assertThat(BulkEdit.removeTag("午饭 #餐饮 #报销", "餐饮")).isEqualTo("午饭 #报销")
    }

    @Test
    fun `removing a tag that is not there is a no-op`() {
        assertThat(BulkEdit.removeTag("午饭 #报销", "餐饮")).isEqualTo("午饭 #报销")
    }

    @Test
    fun `removing the last tag leaves the plain text`() {
        assertThat(BulkEdit.removeTag("午饭 #餐饮", "餐饮")).isEqualTo("午饭")
        assertThat(BulkEdit.removeTag("#餐饮", "餐饮")).isEmpty()
    }

    @Test
    fun `removing a tag matches case insensitively`() {
        assertThat(BulkEdit.removeTag("coffee #Food", "food")).isEqualTo("coffee")
    }

    @Test
    fun `a date edit keeps the time of day`() {
        val original = millis(2024, 3, 5, 14, 23, 45)
        val result = BulkEdit.applyDate(original, "2026-01-31", zone)
        assertThat(result).isNotNull

        val calendar = Calendar.getInstance(zone).apply { timeInMillis = result!! }
        assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2026)
        assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.JANUARY)
        assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(31)
        assertThat(calendar.get(Calendar.HOUR_OF_DAY)).isEqualTo(14)
        assertThat(calendar.get(Calendar.MINUTE)).isEqualTo(23)
        assertThat(calendar.get(Calendar.SECOND)).isEqualTo(45)
    }

    @Test
    fun `a date edit accepts slash and dot separators and unpadded parts`() {
        val original = millis(2024, 3, 5, 9, 0, 0)
        for (text in listOf("2026/2/1", "2026.2.1", "2026-02-01")) {
            val calendar = Calendar.getInstance(zone).apply {
                timeInMillis = BulkEdit.applyDate(original, text, zone)!!
            }
            assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2026)
            assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.FEBRUARY)
            assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(1)
        }
    }

    @Test
    fun `a date edit onto a short month does not roll over`() {
        // 31 Jan -> 28 Feb must stay in February, not spill into March.
        val original = millis(2026, 1, 31, 8, 0, 0)
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = BulkEdit.applyDate(original, "2026-02-28", zone)!!
        }
        assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.FEBRUARY)
        assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(28)
    }

    @Test
    fun `unparsable or out-of-range dates are rejected`() {
        val original = millis(2026, 1, 31, 8, 0, 0)
        for (text in listOf("", "   ", "not a date", "2026", "2026-13-01", "2026-02-30", "2026-00-10", "2026-01-32")) {
            assertThat(BulkEdit.applyDate(original, text, zone))
                .describedAs("must reject %s", text)
                .isNull()
        }
        assertThat(BulkEdit.applyDate(original, null, zone)).isNull()
    }

    @Test
    fun `only the tag field is a tag field`() {
        assertThat(BulkEditField.values().filter { it.isTag }).containsExactly(BulkEditField.Tag)
        assertThat(BulkEditField.values()).hasSize(5)
    }

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis
}
