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
 * An explicit "this record was deleted" marker (feature ② v2, plan §7.1).
 *
 * Deletions must be carried as evidence rather than inferred from absence: an
 * absent record in a truncated listing looks exactly like a deleted one, and
 * treating the two the same is how both devices end up deleting the data
 * (plan §7.3, constraint 2). Only [org.gnucash.android.sync.ThreeWayMerge.planDeletions]
 * — which demands **confirmed** absence — is allowed to produce these.
 *
 * @param deletedAt epoch millis at which the deletion was confirmed.
 * @param deviceId  which device confirmed it (audit trail only).
 */
class Tombstone(
    var recordType: SyncRecordType = SyncRecordType.TRANSACTION,
    var recordKey: String = "",
    var deletedAt: Long = 0L,
    var deviceId: String? = null
) : BaseModel() {

    override fun toString(): String =
        "{type: ${recordType.id}, key: $recordKey, deletedAt: $deletedAt}"
}
