package org.gnucash.android.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 手工填写 a report period as two `20260129` fields.
 *
 * Format and strict parsing are [ManualDate]'s job (and are pinned there); what this layer
 * adds is the **order** rule. A range whose end precedes its start cannot be expressed in
 * the calendar widget, so nothing else in the app would notice it — the report would simply
 * come back empty and look broken.
 */
class ManualDateRangeTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun `two days name a range from the start of the earlier to the start of the later`() {
        val range = ManualDateRange.parse("20260129", "20260315", zone)

        assertThat(range).isNotNull
        assertThat(range!!.first).isEqualTo(millis(2026, 1, 29))
        assertThat(range.second).isEqualTo(millis(2026, 3, 15))
    }

    @Test
    fun `a single day is a valid range`() {
        val range = ManualDateRange.parse("20260129", "20260129", zone)

        assertThat(range).isNotNull
        assertThat(range!!.first).isEqualTo(range.second)
    }

    @Test
    fun `a reversed range is refused instead of silently drawing an empty report`() {
        assertThat(ManualDateRange.parse("20260315", "20260129", zone)).isNull()
    }

    @Test
    fun `a day that is not a real date is refused`() {
        assertThat(ManualDateRange.parse("20260230", "20260315", zone)).isNull()
        assertThat(ManualDateRange.parse("20260129", "20261301", zone)).isNull()
    }

    @Test
    fun `anything that is not eight digits is refused`() {
        assertThat(ManualDateRange.parse(null, "20260315", zone)).isNull()
        assertThat(ManualDateRange.parse("20260129", "", zone)).isNull()
        assertThat(ManualDateRange.parse("2026/1/29", "20260315", zone)).isNull()
    }

    @Test
    fun `separators are tolerated on both ends`() {
        val range = ManualDateRange.parse("2026-01-29", "2026.03.15", zone)

        assertThat(range).isNotNull
        assertThat(range!!.first).isEqualTo(millis(2026, 1, 29))
        assertThat(range.second).isEqualTo(millis(2026, 3, 15))
    }

    private fun millis(year: Int, month: Int, day: Int): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
    }.timeInMillis
}
