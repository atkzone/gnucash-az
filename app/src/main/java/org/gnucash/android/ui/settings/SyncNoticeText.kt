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

import androidx.annotation.StringRes
import org.gnucash.android.R
import org.gnucash.android.sync.SyncNotice
import org.gnucash.android.sync.SyncOutcome

/**
 * The one place that turns a finished sync round into words.
 *
 * The sync layer carries no resource ids on purpose (so it stays plain JVM), and this file is
 * the other half of that split — the same arrangement as [WebDavNoticeText], for the same
 * reason: a `when` over an exhaustive enum is the compiler's business, but **two branches that
 * render the same string** is a mistake no compiler catches, so `SyncNoticeTextTest` asserts
 * the ids are pairwise distinct.
 *
 * An unusable connection is deliberately **not** given its own wording here. It is the same
 * eleven typed problems the rest of the page already explains, and they belong on the row the
 * user has to fix — see [WebDavNoticeText.preferenceKeyOf].
 */
object SyncNoticeText {

    @StringRes
    fun of(notice: SyncNotice): Int = when (notice) {
        SyncNotice.NOTHING_TO_DO -> R.string.webdav_sync_nothing_to_do
        SyncNotice.SYNCED -> R.string.webdav_sync_synced
        SyncNotice.DEFERRED_SOMETHING -> R.string.webdav_sync_deferred
        SyncNotice.CONFLICTS_TO_REVIEW -> R.string.webdav_sync_conflicts
        SyncNotice.SOMETHING_FAILED -> R.string.webdav_sync_failed_records
        SyncNotice.RESTART_NEEDED -> R.string.webdav_sync_restart_needed
        SyncNotice.WRONG_FOLDER -> R.string.webdav_sync_wrong_folder
        SyncNotice.LISTING_INCOMPLETE -> R.string.webdav_sync_listing_incomplete
    }

    /**
     * The sentence for a whole outcome.
     *
     * [SyncOutcome.NotUsable] resolves through [WebDavNoticeText.of] rather than a string of its
     * own: the user must see "the address needs an `https://` prefix", not "sync failed".
     */
    @StringRes
    fun of(outcome: SyncOutcome): Int = when (outcome) {
        is SyncOutcome.NotUsable -> WebDavNoticeText.of(outcome.problem)
        is SyncOutcome.Failed -> R.string.webdav_sync_broke
        is SyncOutcome.Ran -> of(outcome.notice)
    }
}
