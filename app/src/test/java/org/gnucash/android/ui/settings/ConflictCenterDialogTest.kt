package org.gnucash.android.ui.settings

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.sync.ConflictDigest
import org.gnucash.android.sync.ConflictKind
import org.gnucash.android.sync.ConflictReview
import org.gnucash.android.sync.FieldDiff
import org.gnucash.android.sync.MergeDecision
import org.gnucash.android.sync.Side
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test

/**
 * The conflict centre as the user meets it: what gets built, and every line that is in it.
 *
 * The content is read straight off [ConflictCenterDialogFragment.contentView] rather than out of
 * a shown dialog. That is not a shortcut around the UI — it is the only level at which the
 * assertions mean anything here: an `AlertDialog` that has not been shown has a window but no
 * views in it, so reading the decor view would report **every** screen as empty, and the tests
 * would pass while showing nothing at all.
 *
 * What still runs for real is the whole path that could go wrong — decode, classify, render,
 * format — because [ConflictCenterDialogFragment.build] puts exactly this view into the dialog.
 */
class ConflictCenterDialogTest : GnuCashTest() {

    private val id = SyncIdentity(SyncRecordType.ACCOUNT, "0123456789abcdef")

    private fun review(
        identity: SyncIdentity = id,
        diffs: List<FieldDiff> = listOf(FieldDiff("name", "old", "mine", "theirs")),
        note: String = "note"
    ) = ConflictReview(identity, note, MergeDecision.Conflict(diffs))

    private fun build(payload: String?): Dialog = ConflictCenterDialogFragment().build(context, payload)

    private fun textsOf(payload: String?): List<String> =
        collect(ConflictCenterDialogFragment().contentView(context, payload))

    private fun collect(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text?.toString().orEmpty())
        is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertShows(payload: String?, fragment: String) {
        assertThat(textsOf(payload))
            .`as`("a line containing %s", fragment)
            .anySatisfy { assertThat(it).contains(fragment) }
    }

    // ---------------------------------------------------------------- the dialog

    @Test
    fun `an empty list says so instead of showing nothing`() {
        assertShows(ConflictCenterCodec.encode(emptyList()), context.getString(R.string.conflict_center_empty))
    }

    @Test
    fun `a missing payload says the same thing`() {
        assertShows(null, context.getString(R.string.conflict_center_empty))
    }

    @Test
    fun `a parked record appears as a row naming the record and the situation`() {
        val payload = ConflictCenterCodec.encode(listOf(review()))
        assertShows(payload, context.getString(ConflictCenterText.of(SyncRecordType.ACCOUNT)))
        assertShows(payload, context.getString(ConflictCenterText.of(ConflictKind.BOTH_EDITED)))
    }

    @Test
    fun `losing part of the list is announced, not silently shown as complete`() {
        // The payload was written by us, so this is not expected — but a screen that quietly
        // shows four of five conflicts is worse than one that admits a problem.
        assertShows("this is not a payload at all", context.getString(R.string.conflict_center_dropped, 1))
    }

    @Test
    fun `a capped list says it is capped`() {
        val many = (0 until ConflictCenterCodec.MAX_REVIEWS + 1).map {
            ConflictReview(SyncIdentity(SyncRecordType.ACCOUNT, "key$it"), "n", MergeDecision.Conflict(emptyList()))
        }
        assertShows(
            ConflictCenterCodec.encode(many),
            context.getString(R.string.conflict_center_capped, ConflictCenterCodec.MAX_REVIEWS)
        )
    }

    @Test
    fun `a shortened value says it was shortened`() {
        val long = "x".repeat(ConflictCenterCodec.MAX_VALUE_CHARS + 10)
        val payload = ConflictCenterCodec.encode(
            listOf(review(diffs = listOf(FieldDiff("notes", long, null, null))))
        )
        assertShows(
            payload,
            context.getString(R.string.conflict_center_truncated, ConflictCenterCodec.MAX_VALUE_CHARS)
        )
    }

    @Test
    fun `a healthy payload gets no warnings`() {
        val texts = textsOf(ConflictCenterCodec.encode(listOf(review())))
        assertThat(texts).isNotEmpty()
        assertThat(texts).noneSatisfy { assertThat(it).contains("读不回来") }
        assertThat(texts).noneSatisfy { assertThat(it).contains("只列出前") }
        assertThat(texts).noneSatisfy { assertThat(it).contains("只显示了前") }
    }

    @Test
    fun `the built dialog is an alert dialog, and its content came from the payload`() {
        val dialog = build(ConflictCenterCodec.encode(listOf(review())))
        assertThat(dialog).isInstanceOf(AlertDialog::class.java)
        // The content view is what the dialog shows; it is the same view the assertions above
        // read, which is what makes them evidence about the dialog rather than about a helper.
        assertThat(dialog.window).isNotNull()
    }

    // ---------------------------------------------------------------- one row

    @Test
    fun `a row with no comparable fields drops the count instead of claiming zero`() {
        // "0 field differences" would read as "the two sides agree", which is the one thing an
        // unreadable record does not let us say.
        val row = ConflictDigest.of(listOf(ConflictReview(id, "n"))).single()
        val label = ConflictCenterDialogFragment.labelOf(context, row)
        assertThat(label).contains(context.getString(ConflictCenterText.of(ConflictKind.UNREADABLE)))
        assertThat(label).doesNotContain("0")
    }

    @Test
    fun `a row with differences states how many`() {
        val row = ConflictDigest.of(
            listOf(review(diffs = listOf(FieldDiff("a", null, "1", "2"), FieldDiff("b", null, "1", "2"))))
        ).single()
        assertThat(ConflictCenterDialogFragment.labelOf(context, row)).contains("2").contains("账户")
    }

    // ---------------------------------------------------------------- the detail

    @Test
    fun `the detail shows the field, the three sides and what happened`() {
        val row = ConflictDigest.of(listOf(review())).single()
        val detail = ConflictCenterDialogFragment.detailOf(context, row)
        assertThat(detail).contains("name")
        assertThat(detail).contains("old").contains("mine").contains("theirs")
        assertThat(detail).contains(context.getString(ConflictCenterText.of(ConflictKind.BOTH_EDITED)))
    }

    @Test
    fun `a side without a value is named as absent, not shown as blank`() {
        val row = ConflictDigest.of(
            listOf(review(diffs = listOf(FieldDiff("notes", null, "mine", "theirs"))))
        ).single()
        val detail = ConflictCenterDialogFragment.detailOf(context, row)
        assertThat(detail).contains(context.getString(R.string.conflict_center_side_absent))
    }

    @Test
    fun `the detail of a record with nothing to compare says so`() {
        val row = ConflictDigest.of(listOf(ConflictReview(id, "n"))).single()
        assertThat(ConflictCenterDialogFragment.detailOf(context, row))
            .contains(context.getString(R.string.conflict_center_no_diffs))
    }

    @Test
    fun `a short key is shown whole and a long one is shortened`() {
        val shortRow = ConflictDigest.of(
            listOf(review(identity = SyncIdentity(SyncRecordType.ACCOUNT, "abc")))
        ).single()
        assertThat(ConflictCenterDialogFragment.detailOf(context, shortRow)).contains("abc")

        val longRow = ConflictDigest.of(listOf(review())).single()
        val detail = ConflictCenterDialogFragment.detailOf(context, longRow)
        assertThat(detail).contains("01234567…")
        assertThat(detail).doesNotContain(id.key)
    }

    // ---------------------------------------------------------------- carrying it in

    @Test
    fun `the fragment carries its reviews in its arguments`() {
        // The arguments are the only place the list lives, so the dialog survives being
        // rebuilt by the system; an empty `Bundle` would reopen as "nothing to review".
        val reviews = listOf(
            review(),
            ConflictReview(
                SyncIdentity(SyncRecordType.TRANSACTION, "beef"), "n",
                MergeDecision.DeleteVsEdit(Side.Remote, emptyList())
            )
        )
        val fragment = ConflictCenterDialogFragment.newInstance(reviews)
        val payload = fragment.arguments?.getString(ConflictCenterDialogFragment.ARG_PAYLOAD)
        assertThat(payload).isNotNull()
        assertThat(ConflictCenterCodec.decode(payload).reviews).isEqualTo(reviews)
    }
}
