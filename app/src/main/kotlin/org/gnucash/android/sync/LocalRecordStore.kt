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

/**
 * The **local** half of a sync round: the records as this device currently holds
 * them (feature ② v2).
 *
 * A seam rather than a direct adapter call, for the same reason [SyncRound] takes
 * a transport seam: the round's job is to decide *what* to change, and that
 * decision must be assertable against an in-memory store without a full book
 * database. The production implementation is the backing adapter for each
 * [SyncRecordType]; wiring it in is a separate, lower-risk step because by then
 * every decision below it is already covered by tests.
 *
 * Records are exchanged as [RecordState] — a decoded field map — never as raw
 * rows, so "what the merge compares" is one shape everywhere.
 */
interface LocalRecordStore {

    /**
     * The record types this store can represent.
     *
     * Declared rather than discovered, so a caller can configure the round from the store
     * itself (`types = store.supportedTypes`) instead of repeating the list and silently
     * disagreeing with it.
     */
    val supportedTypes: Set<SyncRecordType>

    /**
     * The local records of the given [types], as decoded field maps.
     *
     * @throws UnsupportedRecordTypeException when any of [types] is not in [supportedTypes].
     *         Returning an empty list instead would be read by the merge as *the user
     *         deleted every record of that type on this device*, which deletes the peer's
     *         copies on the next push. "I have none" and "I cannot see any" must never have
     *         the same shape.
     */
    fun list(types: Collection<SyncRecordType>): List<RecordState>

    /**
     * Writes (or replaces) one record from an adopted remote state.
     *
     * Called only for a [SyncVerb.PULL]; the round has already decided the change is the one
     * to keep. Whether the write is *applicable* is still the store's call, because the
     * record may depend on something that is not in the book yet (a parent account, a
     * commodity) — and inventing that dependency is worse than waiting a round.
     *
     * @return `true` when the record was written. `false` means the store declined, and
     *         logged why. A caller must then leave that record's base **untouched**: a base
     *         recorded for a record that was never written reads, on the next round, as
     *         "the user deleted it here" and would delete the peer's copy. See [SyncRound].
     */
    fun write(state: RecordState): Boolean

    /**
     * Deletes one record locally.
     *
     * @return `true` when a record was actually removed. A `false` is not an error:
     *         adopting a peer deletion for a record that is already gone locally is
     *         exactly the idempotent case a re-run produces.
     */
    fun delete(identity: SyncIdentity): Boolean
}

/**
 * Thrown when a store is asked about a record type it cannot represent (feature ② v2).
 *
 * This is an error rather than an empty result, and the distinction is the whole point:
 * "this device has no records of that type" and "this store cannot see records of that type"
 * are opposite facts with the same shape, and the merge reads the first one as a mass local
 * deletion — which is how a partially-wired store quietly deletes the other side's book.
 */
class UnsupportedRecordTypeException(val recordType: SyncRecordType) :
    IllegalArgumentException("this store does not support ${recordType.id} records")
