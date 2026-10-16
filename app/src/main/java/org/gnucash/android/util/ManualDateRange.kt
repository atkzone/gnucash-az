package org.gnucash.android.util

import java.util.TimeZone

/**
 * 手工填写 a **date range** — the report period, typed as two `20260129` fields.
 *
 * The report screen's calendar is a *range* control (`CalendarPickerView`), so 「手工填写」
 * there has to name two days rather than one. Format, normalisation and strict parsing all
 * stay in [ManualDate]; the only rule that needs a home of its own is "the end may not come
 * before the start" — the calendar widget makes that impossible to express, but two typed
 * fields can say it, and a report whose period ends before it starts draws an empty table
 * that reads as a bug.
 */
object ManualDateRange {

    /** @return the two day-starts, or `null` when a field is unparsable or the order is reversed. */
    fun parse(
        from: String?,
        to: String?,
        zone: TimeZone = TimeZone.getDefault()
    ): Pair<Long, Long>? {
        val start = ManualDate.parse(from, zone) ?: return null
        val end = ManualDate.parse(to, zone) ?: return null
        if (end < start) return null
        return start to end
    }
}
