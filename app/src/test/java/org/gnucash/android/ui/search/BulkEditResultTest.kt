package org.gnucash.android.ui.search

import android.os.Bundle
import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test

/**
 * The uids a bulk edit carries back out of its dialog.
 *
 * Without them the screen has to fall back to "whatever is selected right now" — and the
 * action bar is closed *before* the batch starts, which can clear that selection. The
 * result then has nothing to act on, and "保存" looks like it did nothing at all (the
 * reported 卡住 / 数据没写入). They travel as an intent-style string array, so the
 * round-trip is worth pinning.
 */
class BulkEditResultTest : GnuCashTest() {

    @Test
    fun `uidsOf reads the carried uids`() {
        val result = Bundle().apply {
            putStringArray(BulkEditTransactionsDialogFragment.EXTRA_UIDS, arrayOf("a", "b", "c"))
        }

        assertThat(BulkEditTransactionsDialogFragment.uidsOf(result))
            .containsExactly("a", "b", "c")
    }

    @Test
    fun `uidsOf answers null when nothing was carried`() {
        assertThat(BulkEditTransactionsDialogFragment.uidsOf(Bundle())).isNull()
    }

    @Test
    fun `uidsOf treats an empty array as nothing`() {
        val result = Bundle().apply {
            putStringArray(BulkEditTransactionsDialogFragment.EXTRA_UIDS, emptyArray())
        }

        assertThat(BulkEditTransactionsDialogFragment.uidsOf(result)).isNull()
    }

    @Test
    fun `the field is read back too`() {
        val result = Bundle().apply {
            putString(BulkEditTransactionsDialogFragment.EXTRA_FIELD, BulkEditField.Notes.name)
        }

        assertThat(BulkEditTransactionsDialogFragment.fieldOf(result)).isEqualTo(BulkEditField.Notes)
    }

    @Test
    fun `an unknown field name is refused`() {
        val result = Bundle().apply {
            putString(BulkEditTransactionsDialogFragment.EXTRA_FIELD, "not-a-field")
        }

        assertThat(BulkEditTransactionsDialogFragment.fieldOf(result)).isNull()
    }
}
