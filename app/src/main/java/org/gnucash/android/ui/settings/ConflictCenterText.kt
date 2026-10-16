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
import org.gnucash.android.sync.ConflictKind
import org.gnucash.android.sync.FieldDeltaKind
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncRecordType

/**
 * One place that turns a conflict-centre **value** into the words the user reads.
 *
 * Keeping it here — and out of the fragment — is what lets the mapping be asserted without
 * inflating a dialog, and what keeps the `when`s exhaustive: adding a [ConflictKind] or a
 * [FieldDeltaKind] fails to compile until it has something to say, instead of silently
 * rendering as a blank row.
 *
 * The record-type names are the ones already used elsewhere in the app. The **field** names
 * deliberately are not translated: the sync layer keys fields by database column
 * (`AccountFields` / `TransactionFields` document why), and inventing friendlier names here
 * would let the screen and the stored data disagree — most sharply for `name` and
 * `description`, whose model meanings are the reverse of their column names.
 */
object ConflictCenterText {

    /** The situation behind one row. */
    @StringRes
    fun of(kind: ConflictKind): Int = when (kind) {
        ConflictKind.BOTH_EDITED -> R.string.conflict_kind_both_edited
        ConflictKind.DELETED_THERE_EDITED_HERE -> R.string.conflict_kind_deleted_there_edited_here
        ConflictKind.DELETED_HERE_EDITED_THERE -> R.string.conflict_kind_deleted_here_edited_there
        ConflictKind.UNREADABLE -> R.string.conflict_kind_unreadable
    }

    /** What happened to one field. */
    @StringRes
    fun of(delta: FieldDeltaKind): Int = when (delta) {
        FieldDeltaKind.LOCAL_CHANGED -> R.string.conflict_delta_local_changed
        FieldDeltaKind.REMOTE_CHANGED -> R.string.conflict_delta_remote_changed
        FieldDeltaKind.BOTH_CHANGED -> R.string.conflict_delta_both_changed
        FieldDeltaKind.ONLY_LOCAL -> R.string.conflict_delta_only_local
        FieldDeltaKind.ONLY_REMOTE -> R.string.conflict_delta_only_remote
        FieldDeltaKind.LOCAL_DROPPED -> R.string.conflict_delta_local_dropped
        FieldDeltaKind.REMOTE_DROPPED -> R.string.conflict_delta_remote_dropped
        FieldDeltaKind.BOTH_DROPPED -> R.string.conflict_delta_both_dropped
    }

    /** The kind of record a row is about. */
    @StringRes
    fun of(type: SyncRecordType): Int = when (type) {
        SyncRecordType.BOOK -> R.string.conflict_record_type_book
        SyncRecordType.ACCOUNT -> R.string.conflict_record_type_account
        SyncRecordType.TRANSACTION -> R.string.conflict_record_type_transaction
        SyncRecordType.SPLIT -> R.string.conflict_record_type_split
    }

    /**
     * Which sides a row may be resolved to (feature ② v3).
     *
     * An unreadable row can only be resolved by keeping this device's version: "adopt the
     * peer's" needs the peer's **content**, and by definition there is none. Offering a button
     * that could never be honoured would be worse than not offering it — the answer would be
     * stored, silently ignored on every round, and the row would keep coming back while the
     * user believes they settled it.
     *
     * Order is deliberate: "keep this device's version" first, matching the dialog's buttons.
     */
    fun sidesFor(kind: ConflictKind): List<ResolveSide> = when (kind) {
        ConflictKind.UNREADABLE -> listOf(ResolveSide.LOCAL)
        else -> ResolveSide.entries
    }

    /** The label of one resolution choice. */
    @StringRes
    fun of(side: ResolveSide): Int = when (side) {
        ResolveSide.LOCAL -> R.string.conflict_center_keep_local
        ResolveSide.REMOTE -> R.string.conflict_center_take_remote
    }
}
