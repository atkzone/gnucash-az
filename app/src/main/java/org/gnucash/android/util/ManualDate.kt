package org.gnucash.android.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The `20260129` shape of a date: what the 手工填写 field shows and what it accepts.
 *
 * Pure and dependency-free on purpose. "The eight digits the user typed", "the day those
 * digits name" and "the moment the rest of the app stores" are three different things,
 * and collapsing them carelessly is how a manually entered date quietly lands on the
 * wrong day (or on 3 March when the user typed 20260230).
 */
object ManualDate {

    /** Digits only, so the interpretation never depends on the locale's date order. */
    const val PATTERN = "yyyyMMdd"

    private const val DIGITS = 8

    /** @return the day of [millis] as `20260129`, in [zone]. */
    fun format(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        formatter(zone).format(Date(millis))

    /**
     * Everything but the digits, capped at eight — so pasting `2026-01-29` still yields
     * `20260129` instead of being rejected.
     *
     * A form that has already lost a leading zero (`2026/1/29` → `2026129`) is only seven
     * digits and is refused downstream rather than guessed at: `2026119` could be either
     * January 19th or November 9th, and a coin flip on the user's date is worse than a
     * re-type.
     */
    fun digitsOf(text: String?): String =
        text.orEmpty().filter { it.isDigit() }.take(DIGITS)

    /**
     * @return the instant the typed day starts in [zone], or `null` when [text] is not a
     *         real date.
     *
     * `20260230` is eight digits and not a day: a lenient parse rolls it forward to
     * 3 March, which the user would never notice. Strict parsing refuses it, so the
     * caller can say "check the date" instead of writing the wrong one.
     */
    fun parse(text: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        val digits = digitsOf(text)
        if (digits.length != DIGITS) return null
        val position = ParsePosition(0)
        val date = formatter(zone).parse(digits, position) ?: return null
        if (position.index != DIGITS) return null
        return startOfDay(date.time, zone)
    }

    /** @return [millis] moved to 00:00:00.000 of its own day in [zone]. */
    fun startOfDay(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** A fresh formatter per call: [SimpleDateFormat] is not thread-safe. */
    private fun formatter(zone: TimeZone): SimpleDateFormat =
        SimpleDateFormat(PATTERN, Locale.US).apply {
            isLenient = false
            timeZone = zone
        }
}
