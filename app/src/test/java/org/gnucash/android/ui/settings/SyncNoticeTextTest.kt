package org.gnucash.android.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.R
import org.gnucash.android.sync.SyncNotice
import org.gnucash.android.sync.SyncOutcome
import org.gnucash.android.sync.SyncRoundResult
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.Test

/**
 * The words for a finished sync round.
 *
 * The compiler checks that the `when` is exhaustive and that the ids resolve; the two things it
 * cannot check are asserted here — that no two branches **render the same string**, and that
 * the six counts keep their positions (a reordered `getString` argument list compiles happily
 * and prints "uploaded 3 · downloaded 1" for "1 uploaded, 3 downloaded").
 */
class SyncNoticeTextTest : GnuCashTest() {

    @Test
    fun `every notice has its own sentence`() {
        val ids = SyncNotice.entries.map { SyncNoticeText.of(it) }
        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `an unusable address is explained by the row, not as a sync failure`() {
        // The same eleven typed problems the one-way path already explains; giving the two-way
        // path its own wording would send the user to the wrong place.
        val outcome = SyncOutcome.NotUsable(WebDavConnection.Problem.MISSING_SCHEME, "detail")
        assertThat(SyncNoticeText.of(outcome))
            .isEqualTo(WebDavNoticeText.of(WebDavConnection.Problem.MISSING_SCHEME))
        assertThat(SyncNoticeText.of(outcome)).isNotEqualTo(R.string.webdav_sync_broke)
    }

    @Test
    fun `a round that broke has its own wording`() {
        assertThat(SyncNoticeText.of(SyncOutcome.Failed("boom")))
            .isEqualTo(R.string.webdav_sync_broke)
    }

    @Test
    fun `a finished round resolves through its notice`() {
        val result = SyncRoundResult(completed = true)
        assertThat(SyncNoticeText.of(SyncOutcome.Ran(SyncNotice.WRONG_FOLDER, result)))
            .isEqualTo(R.string.webdav_sync_wrong_folder)
    }

    @Test
    fun `the counts line keeps every number in its own slot`() {
        val text = context.getString(R.string.webdav_sync_counts, 1, 2, 3, 4, 5, 6)
        assertThat(text).isEqualTo("上传 1 · 下载 2 · 云端删 3 · 本地删 4 · 延后 5 · 失败 6")
    }
}
