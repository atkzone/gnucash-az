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
 * The kinds of record that participate in the bidirectional merge (feature ② v2).
 *
 * A record is addressed by `(type, key)` where `key` is the GnuCash GUID — the
 * only identifier that is stable across devices and across an export/import round
 * trip (see plan §7.1).
 *
 * [id] is the value persisted in `sync_state.record_type` /
 * `tombstones.record_type`; it is deliberately **not** the enum name, so renaming
 * the Kotlin constant cannot silently orphan existing rows.
 *
 * Not every constant is a type the store carries *independently*. `BOOK` is the whole book, and
 * `SPLIT` travels inside its transaction (see [TransactionFields]) — a store that supports
 * [TRANSACTION] therefore still refuses a standalone [SPLIT] record rather than answering
 * "none", which is why the refusal is an exception and not an empty list.
 */
enum class SyncRecordType(val id: String) {
    BOOK("book"),
    ACCOUNT("account"),
    TRANSACTION("transaction"),
    SPLIT("split");

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Resolves a persisted [id], or `null` when the row is from a newer version. */
        fun fromId(id: String?): SyncRecordType? = id?.let { byId[it] }
    }
}
