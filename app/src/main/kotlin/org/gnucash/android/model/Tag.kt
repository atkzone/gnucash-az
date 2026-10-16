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

import org.gnucash.android.util.TagCodec

/**
 * A first-class tag (feature ⑧).
 *
 * The name is the identity (case-insensitive — `tags.name` is `COLLATE NOCASE`),
 * while [uid] lets the row participate in the model/adapter machinery and be
 * referenced from `transaction_tags` without a name-based join. [colorCode] is
 * reserved for the chip UI; it may be `null`.
 */
class Tag(name: String = "") : BaseModel() {
    var name: String = TagCodec.normalizeName(name) ?: ""
        set(value) {
            field = TagCodec.normalizeName(value) ?: ""
        }

    var colorCode: String? = null

    override fun toString(): String = "{name: $name}"
}
