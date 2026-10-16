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
package org.gnucash.android.sync

import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.sync.webdav.WebDavTransportFactory
import timber.log.Timber

/**
 * Runs **one** bidirectional round against a connection the user typed (feature ② v2).
 *
 * This is the thin adapter between "the settings page has four text fields" and
 * [SyncRound], which wants a client, a state table and a store. It exists as its own class for
 * the same reason [WebDavSyncController] does — to keep the one ordering that is easy to get
 * wrong in a single place:
 *
 *  - **validate before dialling.** [connection.check] is consulted first, and an unusable
 *    connection produces [SyncOutcome.NotUsable] with **zero requests**. Reporting a missing
 *    `https://` as a sync failure would send the user to look at the server.
 *
 * Two things it deliberately does *not* do:
 *
 *  - **it does not decide what a round means.** [SyncNotices.of] does that, from the result, so
 *    the mapping is assertable without a network;
 *  - **it does not swallow a crash.** [SyncRound] is written so that a transport failure arrives
 *    as a typed outcome, but the store and the state table are SQLite and can still throw. A
 *    throw that escaped would be caught by the fragment's generic background-thread guard and
 *    turn into "nothing happened at all" — the user taps sync, nothing appears, and the log has
 *    one line nobody reads. [SyncOutcome.Failed] instead carries it to the screen.
 *
 * Free of Android dependencies: the transport arrives as a factory, and the store, the state
 * table and the clock are injected (see `SyncRunnerTest`).
 *
 * @param state   the `sync_state` / `tombstones` tables — the common ancestor of the merge.
 * @param store   this device's records, for the types the store supports.
 * @param types   which record types this round carries. Defaults to all of them.
 * @param deviceId stamped onto tombstones so a deletion can be attributed later; `null` is
 *                 tolerated (the column is nullable) and simply leaves it unattributed.
 */
class SyncRunner(
    private val state: SyncStateDbAdapter,
    private val store: LocalRecordStore,
    private val transportFactory: WebDavTransportFactory,
    private val types: Collection<SyncRecordType> = SyncRecordType.entries,
    private val now: () -> Long = System::currentTimeMillis,
    private val deviceId: String? = null
) {

    /**
     * One round, end to end.
     *
     * A `RestartNeeded` round is **not** retried here on purpose: the correct response to a
     * `412` is to re-list and re-merge, which is another whole round — and looping inside the
     * caller would hide a peer that keeps moving under us behind one long press. The notice
     * says so, and the user's next tap does the right thing.
     */
    fun runOnce(connection: WebDavConnection): SyncOutcome = when (val check = connection.check()) {
        is WebDavConnection.Check.Invalid ->
            SyncOutcome.NotUsable(check.problem, check.detail)

        is WebDavConnection.Check.Ready -> try {
            val result = SyncRound(
                client = check.connect(transportFactory),
                state = state,
                store = store,
                types = types,
                now = now,
                deviceId = deviceId
            ).run()
            SyncOutcome.Ran(SyncNotices.of(result), result)
        } catch (e: Throwable) {
            // Key path: log before turning it into words, so the stack is not lost to a snackbar.
            Timber.e(e, "A sync round threw")
            SyncOutcome.Failed(e.message)
        }
    }
}
