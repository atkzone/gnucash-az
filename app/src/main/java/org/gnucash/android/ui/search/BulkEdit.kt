package org.gnucash.android.ui.search

import org.gnucash.android.R
import org.gnucash.android.util.TagCodec
import java.util.Calendar
import java.util.TimeZone

/**
 * The transaction field a bulk edit writes to.
 *
 * Only transaction-level fields live here: rewriting the accounts or the amount
 * of N transactions at once would have to re-balance every split pair, which is
 * a different (and much larger) feature than "set one value".
 */
enum class BulkEditField(val titleRes: Int, val hintRes: Int, val isTag: Boolean = false) {
    Description(R.string.bulk_field_description, R.string.bulk_edit_text_hint),
    Notes(R.string.bulk_field_notes, R.string.bulk_edit_text_hint),
    Number(R.string.bulk_field_number, R.string.bulk_edit_text_hint),
    Date(R.string.bulk_field_date, R.string.bulk_edit_date_hint),
    Tag(R.string.bulk_field_tag, R.string.bulk_edit_tag_hint, isTag = true)
}

/**
 * Value handling for the bulk edit dialog, kept free of Android so the rules can
 * be asserted directly.
 *
 * Two decisions worth spelling out:
 *  - **A blank text value is a value.** "set the number of these 40 transactions
 *    to nothing" is a legitimate request, so blank means "clear the field", not
 *    "invalid" and not "skip".
 *  - **A date edit keeps the time of day.** The dialog asks for `YYYY-MM-DD`
 *    only; rewriting the timestamp with midnight would flatten every transaction
 *    of the day to 00:00.
 */
object BulkEdit {

    private val DATE_PATTERN = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")

    fun normalizeText(raw: String?): String = raw.orEmpty().trim()

    /**
     * Rewrites the tag block of [text] so that it carries [tag] as well.
     * Idempotent: an already-present tag is not duplicated.
     */
    fun addTag(text: String?, tag: String): String {
        val normalized = TagCodec.normalizeName(tag) ?: return text.orEmpty()
        val names = TagCodec.extract(text) + normalized
        return TagCodec.appendTags(text, names)
    }

    /** Rewrites the tag block of [text] without [tag]. */
    fun removeTag(text: String?, tag: String): String {
        val normalized = TagCodec.normalizeName(tag) ?: return text.orEmpty()
        val names = TagCodec.extract(text).filter { !it.equals(normalized, ignoreCase = true) }
        return TagCodec.appendTags(text, names)
    }

    /**
     * Same day as [text] (accepting `-`, `/` and `.` separators), same time of
     * day as [currentMillis]. Returns `null` for anything unparsable, including
     * calendar overflow like `2026-02-30`.
     */
    fun applyDate(currentMillis: Long, text: String?, zone: TimeZone): Long? {
        val match = DATE_PATTERN.matchEntire(text.orEmpty().trim()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        // Reject overflow (2026-02-30) by round-tripping through a lenient-less check.
        val probe = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day)
        }
        if (probe.get(Calendar.YEAR) != year ||
            probe.get(Calendar.MONTH) != month - 1 ||
            probe.get(Calendar.DAY_OF_MONTH) != day
        ) {
            return null
        }

        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = currentMillis
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val millisecond = calendar.get(Calendar.MILLISECOND)
        // One atomic set: setting the day first would roll over for short months.
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, millisecond)
        return calendar.timeInMillis
    }
}
