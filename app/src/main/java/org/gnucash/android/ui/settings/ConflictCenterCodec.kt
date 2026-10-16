/*
 * Copyright (c) 2026 GnuCash Pocket contributors
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
package org.gnucash.android.ui.settings

import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.FieldDiff
import org.gnucash.android.sync.MergeDecision
import org.gnucash.android.sync.RecordCodec
import org.gnucash.android.sync.Side
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.fieldDiffs

/**
 * Carries a round's parked conflicts from the settings fragment that ran the round to the
 * conflict-centre dialog that shows them.
 *
 * ### Why text rather than a `Parcelable`
 *
 * The conflicts go through a `Bundle` (dialog arguments) so the dialog survives a process
 * recreation with its content intact — unlike a static holder, which would come back empty
 * and make the list look like "there is nothing to review". A `Bundle` can carry a
 * `String`, and `String` is the only payload type that is trivially safe there, so the
 * conflicts are encoded to text on the way in and decoded on the way out.
 *
 * ### Why it is layered on [RecordCodec]
 *
 * The review list is `{record → {field → three values}}`, i.e. already the `key=value`
 * shape. Rather than invent a second escaping scheme (and a second set of escaping bugs),
 * each level is written with the codec that already handles newlines, `=` and `%` in
 * free-form values:
 *
 * ```
 * "<count-ish outer keys>"   RecordCodec over  r0 = RecordCodec over  id / note / decision / d.<i>.<side>
 * ```
 *
 * The outer codec escapes the inner one's newlines, so the two levels cannot be confused
 * for each other no matter what a transaction's `notes` contains.
 *
 * ### The two things that must not be lost
 *
 * 1. **`null` is not `""`.** A field a side does not have is a **missing key**; a field it
 *    has, set to the empty string, is `key=`. Collapsing them would make "the peer has no
 *    such field" read as "the peer blanked it", which is exactly the distinction
 *    [FieldDiff] exists to preserve — so a `null` value is written as an **omitted key**
 *    and never as an empty one.
 * 2. **The payload must stay small.** A `Bundle` crosses a Binder transaction, so the
 *    encoding is capped: at most [MAX_REVIEWS] rows, and each value cut at
 *    [MAX_VALUE_CHARS]. The caps are reported back ([Decoded.capped] / [Decoded.truncated])
 *    so the dialog can say so instead of quietly showing a shortened value as the truth.
 */
object ConflictCenterCodec {

    /**
     * Most reviews carried into the dialog.
     *
     * A round with more conflicts than this is pathological (it means the two devices
     * diverged wholesale), and the rows beyond the cap are all reachable by running the
     * round again after dealing with the first batch — whereas a `Bundle` over the Binder
     * limit is not recoverable at all.
     */
    const val MAX_REVIEWS: Int = 100

    /**
     * Longest field value carried per side, in characters.
     *
     * A transaction's `notes` is unbounded user text; the diff of one is not worth a
     * `TransactionTooLargeException`, and the truncated tail is still on both devices.
     */
    const val MAX_VALUE_CHARS: Int = 512

    /** Marks a value that was cut. A visible `…` beats a silently shortened value. */
    private const val ELLIPSIS = "…"

    private const val KEY_CAPPED = "capped"
    private const val KEY_TRUNCATED = "truncated"
    private const val KEY_ID = "id"
    private const val KEY_NOTE = "note"
    private const val KEY_DECISION = "decision"

    private const val DECISION_NONE = "none"
    private const val DECISION_CONFLICT = "conflict"
    private const val DECISION_DELETE_VS_EDIT = "deleteVsEdit"
    private const val WINNER_LOCAL = "local"
    private const val WINNER_REMOTE = "remote"

    /**
     * What [decode] found.
     *
     * @param reviews   the recovered rows, in their original order.
     * @param dropped   rows that could not be recovered at all (an unreadable level, or an
     *                  identity that would not parse). Reported rather than swallowed: a
     *                  list that is shorter than it should be must not look complete.
     * @param capped    `true` when rows were left out because of [MAX_REVIEWS].
     * @param truncated `true` when at least one value was cut at [MAX_VALUE_CHARS].
     */
    data class Decoded(
        val reviews: List<ConflictReview>,
        val dropped: Int = 0,
        val capped: Boolean = false,
        val truncated: Boolean = false
    )

    /**
     * Encodes the reviews, newest batch first capped at [MAX_REVIEWS].
     *
     * Deterministic: the same input always produces byte-identical text (both levels sort
     * their keys), so the payload can be asserted against a literal in a test.
     */
    fun encode(reviews: List<ConflictReview>): String {
        val kept = reviews.take(MAX_REVIEWS)
        var truncated = false
        val outer = LinkedHashMap<String, String>()
        kept.forEachIndexed { index, review ->
            val (row, cut) = encodeOne(review)
            if (cut) truncated = true
            outer["r$index"] = RecordCodec.encode(row)
        }
        if (kept.size < reviews.size) outer[KEY_CAPPED] = "1"
        // Recorded rather than inferred: a real value may legitimately end in `…`, so
        // "the text ends with the ellipsis" would report truncation that never happened.
        if (truncated) outer[KEY_TRUNCATED] = "1"
        return RecordCodec.encode(outer)
    }

    /**
     * Decodes [encode]'s output.
     *
     * Never throws. A payload that cannot be read at all yields an empty list with
     * `dropped = 1` — "one thing was lost", not "nothing was ever there".
     */
    fun decode(payload: String?): Decoded {
        if (payload.isNullOrEmpty()) return Decoded(emptyList())
        val outer = RecordCodec.decodeOrNull(payload) ?: return Decoded(emptyList(), dropped = 1)

        val order = outer.keys
            .mapNotNull { key -> rowIndex(key)?.let { it to key } }
            .sortedBy { it.first }

        val reviews = ArrayList<ConflictReview>(order.size)
        var dropped = 0
        for ((_, key) in order) {
            val review = decodeOne(outer.getValue(key))
            if (review == null) {
                dropped++
                continue
            }
            reviews += review
        }
        return Decoded(
            reviews = reviews,
            dropped = dropped,
            capped = outer[KEY_CAPPED] == "1",
            truncated = outer[KEY_TRUNCATED] == "1"
        )
    }

    // ------------------------------------------------------------------ one row

    private fun encodeOne(review: ConflictReview): Pair<Map<String, String>, Boolean> {
        var truncated = false

        // The flag is set by the cut itself, never by inspecting the result: a real value
        // may legitimately end in `…` and be exactly the cap long, and reporting that as
        // truncated would put a warning on a value that is complete.
        fun cut(value: String): String {
            if (value.length <= MAX_VALUE_CHARS) return value
            truncated = true
            return value.take(MAX_VALUE_CHARS) + ELLIPSIS
        }

        val row = buildMap {
            put(KEY_ID, review.identity.token)
            put(KEY_NOTE, review.note)
            put(KEY_DECISION, encodeDecision(review.decision))
            review.decision?.let { decision ->
                decision.fieldDiffs.forEachIndexed { index, diff ->
                    put("d.$index.field", diff.field)
                    // A side without a value writes no key at all; see the class doc.
                    diff.base?.let { put("d.$index.base", cut(it)) }
                    diff.local?.let { put("d.$index.local", cut(it)) }
                    diff.remote?.let { put("d.$index.remote", cut(it)) }
                }
            }
        }
        return row to truncated
    }

    private fun decodeOne(inner: String): ConflictReview? {
        val map = RecordCodec.decodeOrNull(inner) ?: return null
        val identity = map[KEY_ID]?.let { SyncIdentity.fromToken(it) } ?: return null
        val diffs = decodeDiffs(map)
        return ConflictReview(
            identity = identity,
            note = map[KEY_NOTE].orEmpty(),
            decision = decodeDecision(map[KEY_DECISION], diffs)
        )
    }

    private fun decodeDiffs(map: Map<String, String>): List<FieldDiff> {
        val indices = map.keys
            .mapNotNull { key -> diffIndex(key) }
            .distinct()
            .sorted()
        return indices.mapNotNull { index ->
            val field = map["d.$index.field"] ?: return@mapNotNull null
            FieldDiff(
                field = field,
                base = map["d.$index.base"],
                local = map["d.$index.local"],
                remote = map["d.$index.remote"]
            )
        }
    }

    // ------------------------------------------------------------------ decisions

    private fun encodeDecision(decision: MergeDecision?): String = when (decision) {
        is MergeDecision.Conflict -> DECISION_CONFLICT
        is MergeDecision.DeleteVsEdit -> when (decision.winner) {
            Side.Local -> "$DECISION_DELETE_VS_EDIT:$WINNER_LOCAL"
            Side.Remote -> "$DECISION_DELETE_VS_EDIT:$WINNER_REMOTE"
        }
        // `null` (unreadable) and any decision that is never parked for review both land
        // here; the round's own `diffs` travel separately, so nothing is lost by it.
        else -> DECISION_NONE
    }

    /**
     * Rebuilds the decision, carrying the diffs back onto it.
     *
     * The diffs are read from the payload rather than recomputed: the dialog has neither
     * the three states nor the merged record, so re-deriving them would mean shipping
     * those too — and would risk showing a *different* diff than the round reported.
     */
    private fun decodeDecision(token: String?, diffs: List<FieldDiff>): MergeDecision? {
        val parts = token?.split(':').orEmpty()
        return when (parts.firstOrNull()) {
            DECISION_CONFLICT -> MergeDecision.Conflict(diffs)
            DECISION_DELETE_VS_EDIT -> when (parts.getOrNull(1)) {
                WINNER_LOCAL -> MergeDecision.DeleteVsEdit(Side.Local, diffs)
                WINNER_REMOTE -> MergeDecision.DeleteVsEdit(Side.Remote, diffs)
                // A winner we cannot read is a decision we cannot describe: report "no
                // comparable decision" rather than guessing which side won.
                else -> null
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The row index of an `r<i>` key, or `null` when the key is not one. */
    private fun rowIndex(key: String): Int? {
        // Explicit rather than `removePrefix`, so this cannot start accepting any other
        // outer key (the payload carries `capped` / `truncated` alongside the rows).
        if (key.length < 2 || key[0] != 'r') return null
        return key.substring(1).toIntOrNull()
    }

    /** The field index of a `d.<i>.<side>` key, or `null` when the key is not one. */
    private fun diffIndex(key: String): Int? {
        if (!key.startsWith("d.")) return null
        return key.substringAfter("d.").substringBefore('.').toIntOrNull()
    }
}
