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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import org.gnucash.android.R
import org.gnucash.android.sync.ConflictDigest
import org.gnucash.android.sync.ConflictRow
import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.FieldDiff
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncIdentity
import timber.log.Timber

/**
 * Shows the records a round parked for review, and what changed in each of them.
 *
 * ### Why a dialog and not a screen with a stored list
 *
 * The parked conflicts are a property of **one round**, not of the account book: they are
 * produced by comparing this device against the peer, and the answer changes the moment
 * either side moves. Persisting them would mean a new table plus the question of when a
 * stored conflict stops being true — so instead they travel with the round, from the
 * fragment that ran it to this dialog, through the arguments `Bundle` (see
 * [ConflictCenterCodec] for the encoding, and why the payload is capped).
 *
 * That also means the dialog is self-contained: it can be recreated by the system at any
 * time from its arguments alone.
 *
 * ### What it does — and what it still does not
 *
 * It shows every parked record, and on a row's detail it lets the user say **which side wins**
 * (feature ② v3). That answer is *stored*, never applied here: it becomes a persisted override
 * that the **next** round's planner reads
 * ([org.gnucash.android.sync.SyncPlan.resolutionsApplied]). Applying it on the spot is not
 * possible — the round that parked the conflict had already decided to touch nothing, and the
 * peer may have moved again by the time the user reads the screen.
 *
 * A row whose peer copy could not be read offers only "keep this device's version": adopting
 * the peer's needs the peer's content, and there is none (see [ConflictCenterText.sidesFor]).
 *
 * It does **not** change the local book and does not re-run the round. The user syncs again to
 * make the choice take effect, which is why the confirmation says so.
 *
 * ### Reading the values
 *
 * Each field is shown as three columns — the common ancestor, this device, the peer — with
 * an empty side rendered as "absent" rather than as a blank cell, because `null` here
 * means "that side has no value for this field", not "the value is empty".
 */
class ConflictCenterDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        build(requireContext(), arguments?.getString(ARG_PAYLOAD))

    /**
     * Builds the dialog from a payload and a context, without needing to be attached.
     *
     * Split out from [onCreateDialog] so the whole construction — the decode, the empty
     * state, the view tree — can be exercised in a unit test without a host activity.
     * A conflict centre that only fails once it is shown is worse than no conflict centre:
     * the user has already been told there are records to review.
     */
    internal fun build(context: Context, payload: String?): Dialog =
        AlertDialog.Builder(context)
            .setTitle(R.string.title_conflict_center)
            .setView(contentView(context, payload))
            .setPositiveButton(R.string.btn_close, null)
            .create()

    /**
     * The dialog's content, decoded and laid out — the whole screen, minus the frame.
     *
     * Exposed rather than kept private so it can be asserted without a window: an unshown
     * `AlertDialog` has a window but no views in it yet, so a test that reads the decor view
     * before showing would "prove" every screen empty. The empty state is rendered here too,
     * rather than as an `AlertDialog` message, so there is exactly one place that decides what
     * the screen says.
     */
    internal fun contentView(context: Context, payload: String?): View =
        contentView(context, ConflictCenterCodec.decode(payload))

    // ------------------------------------------------------------------ view

    private fun contentView(context: Context, decoded: ConflictCenterCodec.Decoded): View {
        if (decoded.dropped > 0) {
            // The list is shorter than it should be; that must be visible in the log even
            // though the dialog cannot make sense of what was lost.
            Timber.w("Conflict centre: %d parked records could not be decoded", decoded.dropped)
        }

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (notice in noticesOf(context, decoded)) {
            container.addView(
                TextView(context).apply {
                    text = notice
                    setPadding(0, 0, 0, padding(context))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
            )
        }

        val rows = ConflictDigest.of(decoded.reviews)
        if (rows.isEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = context.getString(R.string.conflict_center_empty)
                    setPadding(0, padding(context), 0, padding(context))
                }
            )
            return wrap(container)
        }

        for (row in rows) {
            container.addView(
                TextView(context).apply {
                    text = labelOf(context, row)
                    setPadding(0, padding(context), 0, padding(context))
                    setOnClickListener { showDetail(row) }
                }
            )
        }
        return wrap(container)
    }

    /** Wrapped so a book with many conflicting records still fits on screen. */
    private fun wrap(container: LinearLayout): View = ScrollView(container.context).apply {
        addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /**
     * One record, with its three-way field comparison and the two ways to settle it.
     *
     * The buttons are derived from the row's **kind** ([ConflictCenterText.sidesFor]) rather
     * than hard-coded: an unreadable row must not offer "adopt the peer's", because that
     * answer could never be honoured and the row would keep coming back.
     */
    private fun showDetail(row: ConflictRow) {
        val context = context ?: return
        val sides = ConflictCenterText.sidesFor(row.kind)

        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.title_conflict_center)
            .setMessage(detailOf(context, row) + "\n\n" + context.getString(R.string.conflict_center_choice_hint))
            .setPositiveButton(ConflictCenterText.of(sides[0])) { _, _ -> resolve(row, sides[0]) }

        if (sides.size > 1) {
            builder.setNegativeButton(ConflictCenterText.of(sides[1])) { _, _ -> resolve(row, sides[1]) }
            builder.setNeutralButton(R.string.btn_close, null)
        } else {
            // Only one answer exists, so the dismiss action takes the remaining slot. AlertDialog
            // needs at least one button or it renders as a bare message with no way out.
            builder.setNegativeButton(R.string.btn_close, null)
        }
        builder.show()
    }

    /**
     * Stores the user's answer and reports whether it stuck.
     *
     * A confirmation is not decoration here: nothing about the book changes when the button is
     * pressed, so without the toast the only honest reading of the tap is "nothing happened".
     * The failure branch says so in as many words — a dropped answer is the one outcome the
     * user could never discover on their own.
     */
    private fun resolve(row: ConflictRow, side: ResolveSide) {
        val context = context ?: return
        val saved = ConflictResolutionStore.remember(row.identity, side)
        val text = if (saved) {
            context.getString(
                R.string.conflict_center_choice_saved,
                context.getString(ConflictCenterText.of(side))
            )
        } else {
            context.getString(R.string.conflict_center_choice_failed)
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    private fun padding(context: Context): Int =
        (context.resources.displayMetrics.density * 8f).toInt()

    companion object {

        const val TAG: String = "conflict_center"

        /** The arguments key the encoded reviews travel under; visible to tests on purpose. */
        internal const val ARG_PAYLOAD = "payload"

        /** Longest GUID prefix shown; the full key is a 32-hex GUID nobody reads. */
        private const val KEY_PREFIX = 8

        /**
         * Builds the dialog for one round's parked conflicts.
         *
         * Takes the reviews rather than a pre-encoded payload so callers cannot skip the
         * encoding — the arguments are the only place the list lives, so an unencoded list
         * would simply not survive.
         */
        fun newInstance(reviews: List<ConflictReview>): ConflictCenterDialogFragment =
            ConflictCenterDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PAYLOAD, ConflictCenterCodec.encode(reviews))
                }
            }

        /** One list row: what kind of record, what happened, and how much differs. */
        internal fun labelOf(context: Context, row: ConflictRow): String {
            val who = context.getString(ConflictCenterText.of(row.identity.recordType))
            val what = context.getString(ConflictCenterText.of(row.kind))
            return if (row.diffs.isEmpty()) {
                context.getString(R.string.conflict_center_row_no_diffs, who, what)
            } else {
                context.getString(R.string.conflict_center_row, who, what, row.diffs.size)
            }
        }

        /** The dialog-level warnings, in the order they matter. */
        internal fun noticesOf(context: Context, decoded: ConflictCenterCodec.Decoded): List<String> =
            buildList {
                if (decoded.dropped > 0) {
                    add(context.getString(R.string.conflict_center_dropped, decoded.dropped))
                }
                if (decoded.capped) {
                    add(context.getString(R.string.conflict_center_capped, ConflictCenterCodec.MAX_REVIEWS))
                }
                if (decoded.truncated) {
                    add(context.getString(R.string.conflict_center_truncated, ConflictCenterCodec.MAX_VALUE_CHARS))
                }
            }

        /** One record's field-by-field comparison. */
        internal fun detailOf(context: Context, row: ConflictRow): String {
            val head = context.getString(
                R.string.conflict_center_record,
                context.getString(ConflictCenterText.of(row.identity.recordType)) +
                    " / " + shortKey(row.identity)
            )
            // The situation goes on this screen too, not just on the list row: it is what tells
            // the reader whether a missing field means "deleted over there" or "blanked here",
            // and by the time they are looking at the values they have stopped reading the row.
            val situation = context.getString(ConflictCenterText.of(row.kind))
            if (row.diffs.isEmpty()) {
                return listOf(head, situation, context.getString(R.string.conflict_center_no_diffs))
                    .joinToString("\n\n")
            }
            val fields = row.diffs.joinToString("\n") { fieldOf(context, it) }
            return listOf(
                head,
                situation,
                context.getString(R.string.conflict_center_fields_header) + "\n" + fields
            ).joinToString("\n\n")
        }

        private fun fieldOf(context: Context, diff: FieldDiff): String = context.getString(
            R.string.conflict_center_field,
            diff.field,
            context.getString(ConflictCenterText.of(ConflictDigest.deltaKindOf(diff))),
            side(context, diff.base),
            side(context, diff.local),
            side(context, diff.remote)
        )

        /** `null` renders as "absent", never as an empty cell; see the class doc. */
        private fun side(context: Context, value: String?): String =
            value ?: context.getString(R.string.conflict_center_side_absent)

        private fun shortKey(identity: SyncIdentity): String =
            if (identity.key.length <= KEY_PREFIX) identity.key else identity.key.take(KEY_PREFIX) + "…"
    }
}
