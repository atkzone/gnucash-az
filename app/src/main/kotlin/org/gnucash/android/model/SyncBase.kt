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
package org.gnucash.android.model

import org.gnucash.android.sync.SyncRecordType

/**
 * The **base** snapshot of one record (feature ② v2): its state as of the last
 * successful sync.
 *
 * This is the third input of a three-way merge. Without it, "both sides changed
 * the same record" is indistinguishable from "one side changed it", which is how
 * a merge silently discards one side's work.
 *
 * [payload] is opaque to the model layer (see `RecordCodec`); [baseModifiedAt]
 * mirrors the record's `modified_at` at snapshot time and is what the snapshot's
 * watermark is derived from.
 *
 * @param etag the peer's entity tag for this record **as of the last successful
 *        sync**. Two things depend on it and neither is optional: the round can
 *        skip re-downloading a record whose remote etag still equals this one
 *        (otherwise every round downloads the whole book), and a conditional write
 *        needs it as the `If-Match` value (`plan §7.6`). `null` means "never synced,
 *        or the server sent no etag" and forces the conservative path (fetch, and
 *        write without a precondition).
 * @param pendingSince when non-`null`, the epoch millis at which this record was
 *        first seen **absent from a complete peer listing**. A second consecutive
 *        absence promotes it to a tombstone ([org.gnucash.android.sync.ThreeWayMerge.planDeletions]);
 *        if it reappears, the marker is cleared. Keeping it on the base row — rather
 *        than in a side table — means "possibly deleted over there" cannot drift out
 *        of step with the record's own sync state.
 */
class SyncBase(
    var recordType: SyncRecordType = SyncRecordType.TRANSACTION,
    var recordKey: String = "",
    var payload: String = "",
    var baseModifiedAt: Long = 0L,
    var etag: String? = null,
    var pendingSince: Long? = null
) : BaseModel() {

    override fun toString(): String =
        "{type: ${recordType.id}, key: $recordKey, at: $baseModifiedAt, etag: $etag, pending: $pendingSince}"
}
