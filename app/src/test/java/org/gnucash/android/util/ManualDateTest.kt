package org.gnucash.android.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The `20260129` date used by 手工填写.
 *
 * Two things here would be silently wrong with a lenient parse: `20260230` becomes
 * 3 March (the user never notices) and a stray prefix becomes a different year. Both are
 * pinned, because the only other place they would show up is a saved transaction.
 */
class ManualDateTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun `a date is shown as eight digits with the year first`() {
        assertThat(ManualDate.format(millis(2026, 1, 29, 14, 5), zone)).isEqualTo("20260129")
    }

    @Test
    fun `single digit months and days keep their leading zero`() {
        assertThat(ManualDate.format(millis(2026, 1, 9), zone)).isEqualTo("20260109")
        assertThat(ManualDate.format(millis(2026, 12, 31), zone)).isEqualTo("20261231")
    }

    @Test
    fun `typing the digits back names the same day`() {
        val parsed = ManualDate.parse("20260129", zone)
        assertThat(parsed).isNotNull
        assertThat(ManualDate.format(parsed!!, zone)).isEqualTo("20260129")
    }

    @Test
    fun `the parsed day starts at midnight`() {
        val parsed = ManualDate.parse("20260129", zone)!!
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = parsed }
        assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2026)
        assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.JANUARY)
        assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(29)
        assertThat(calendar.get(Calendar.HOUR_OF_DAY)).isZero
        assertThat(calendar.get(Calendar.MINUTE)).isZero
        assertThat(calendar.get(Calendar.SECOND)).isZero
        assertThat(calendar.get(Calendar.MILLISECOND)).isZero
    }

    @Test
    fun `separators are tolerated when the fields are zero padded`() {
        val expected = ManualDate.parse("20260129", zone)
        assertThat(ManualDate.parse("2026-01-29", zone)).isEqualTo(expected)
        assertThat(ManualDate.parse("2026.01.29", zone)).isEqualTo(expected)
        assertThat(ManualDate.parse("2026 01 29", zone)).isEqualTo(expected)
        // 丢掉前导零的形式只剩七位：2026119 既可能是 1 月 19 日也可能是 11 月 9 日，
        // 与其猜，不如拒绝让用户重敲。
        assertThat(ManualDate.parse("2026/1/29", zone)).isNull()
        assertThat(ManualDate.parse("2026119", zone)).isNull()
    }

    @Test
    fun `a day that does not exist is refused instead of rolled forward`() {
        // 宽松解析会把 2026-02-30 收成 3 月 2 日 —— 写进账簿之后没人看得出来。
        assertThat(ManualDate.parse("20260230", zone)).isNull()
        assertThat(ManualDate.parse("20261301", zone)).isNull()
        assertThat(ManualDate.parse("20260132", zone)).isNull()
        assertThat(ManualDate.parse("20260010", zone)).isNull()
    }

    @Test
    fun `anything that is not eight digits is refused`() {
        assertThat(ManualDate.parse(null, zone)).isNull()
        assertThat(ManualDate.parse("", zone)).isNull()
        assertThat(ManualDate.parse("2026", zone)).isNull()
        assertThat(ManualDate.parse("2026012", zone)).isNull()
    }

    @Test
    fun `digits are collected and capped at eight`() {
        assertThat(ManualDate.digitsOf("20260129")).isEqualTo("20260129")
        assertThat(ManualDate.digitsOf("2026-01-29 12:00")).isEqualTo("20260129")
        // 多贴一位不能把年份顶走：截断而不是取前八位之外的任意组合。
        assertThat(ManualDate.digitsOf("2026012912345")).isEqualTo("20260129")
        assertThat(ManualDate.digitsOf("abc")).isEmpty()
        assertThat(ManualDate.digitsOf(null)).isEmpty()
    }

    @Test
    fun `start of day drops the time of day`() {
        val noon = millis(2026, 1, 29, 12, 34)
        assertThat(ManualDate.startOfDay(noon, zone)).isEqualTo(millis(2026, 1, 29))
    }

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis
}
