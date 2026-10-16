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

import androidx.annotation.VisibleForTesting
import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncIdentity
import timber.log.Timber

/**
 * The one place the conflict centre **writes** (feature ② v3).
 *
 * The centre is otherwise a mirror: [ConflictCenterDialogFragment] shows what a round parked and
 * decides nothing. This is the single write path behind its two buttons, kept out of the fragment
 * for two reasons:
 *
 *  * **the failure must be visible.** Writing to the book database can throw, and a write that
 *    threw inside a dialog click listener would be swallowed by the framework — the dialog would
 *    close and the answer would silently not exist. Returning `false` (and logging the stack, per
 *    the project's rule for key paths) lets the screen say "that did not save" instead of
 *    pretending it did. *  * **it is the only path.** A second call site is how "which side wins" starts depending on
 *    where the user tapped. Everything that stores an answer goes through here.
 *
 * It stores an answer; it does not act on it. Applying it is the **next** round's job
 * ([org.gnucash.android.sync.SyncRoundPlanner]), because by the time the user reads the screen
 * the round that parked the conflict is over and the peer may already have moved.
 */
object ConflictResolutionStore {

    /**
     * Records [side] as the answer for [identity], through the active book.
     *
     * @return `true` when it was stored. `false` means it was **not** — the caller must say so.
     */
    fun remember(identity: SyncIdentity, side: ResolveSide): Boolean =
        store(identity, side) { adapter, id, chosen ->
            adapter.putResolution(id.recordType, id.key, chosen)
        }

    /**
     * [remember] with the adapter write injected, so the failure path can be exercised without
     * contriving a database that throws.
     *
     * @return `false` when the write reported failure **or** threw (logged).
     */
    @VisibleForTesting
    internal fun store(
        identity: SyncIdentity,
        side: ResolveSide,
        write: (SyncStateDbAdapter, SyncIdentity, ResolveSide) -> Boolean
    ): Boolean = try {
        // The adapter's own answer is honoured, not assumed: a SQLite insert that fails returns
        // `-1` instead of throwing, so "no exception" is not the same as "it was stored".
        write(SyncStateDbAdapter.instance, identity, side)
    } catch (e: RuntimeException) {
        // Key path: the user just pressed a button, so a silent failure is a button that lies.
        Timber.e(e, "could not store the conflict resolution for %s", identity.token)
        false
    }
}
