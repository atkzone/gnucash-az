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
package org.gnucash.android.util

/**
 * Recognizes the book backup files that can be imported from a file browser.
 *
 * Supported formats (matched case-insensitively):
 *  - `.gnca` — GnuCash Pocket backup, i.e. GnuCash XML compressed with gzip
 *  - `.gnucash` — GnuCash (desktop) book, either plain XML or gzipped
 *
 * Some file managers append the compression suffix to the name they show, so the compound
 * extensions `*.gnca.gz`, `*.gnca.zip`, `*.gnucash.gz` and `*.gnucash.zip` are accepted too.
 *
 * The check is deliberately kept free of Android dependencies so it can be unit tested.
 */
object BackupFileFormat {
    /**
     * Extensions (lower case, without the leading dot) of importable backup files,
     * in the order they are shown to the user.
     */
    val EXTENSIONS = arrayOf("gnca", "gnucash")

    private val ARCHIVE_SUFFIXES = arrayOf("gz", "zip")

    /** Whole-name suffixes accepted by [isSupportedBackupFileName]. */
    private val SUFFIXES: Array<String> = buildList {
        for (extension in EXTENSIONS) {
            add(".$extension")
            for (archive in ARCHIVE_SUFFIXES) add(".$extension.$archive")
        }
    }.toTypedArray()

    /**
     * Returns `true` if [fileName] names a file this app knows how to import.
     *
     * `null`, blank and extension-less names are rejected, as is a name that merely
     * *contains* a supported extension (e.g. `gnucash.gnca.bak`).
     *
     * @param fileName the display name of the picked document, e.g. `my_book.gnca`
     * @return `true` when the name ends with one of the supported suffixes
     */
    fun isSupportedBackupFileName(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        val name = fileName.trim().lowercase()
        for (suffix in SUFFIXES) {
            // `name.length > suffix.length` rejects a file called exactly ".gnca"
            // (that is a hidden file with no base name, not a backup).
            if (name.length > suffix.length && name.endsWith(suffix)) return true
        }
        return false
    }

    /**
     * Human readable list of the supported extensions, e.g. `.gnca / .gnucash`.
     * Used in the message shown when an unsupported file is picked.
     */
    fun displayExtensions(): String = EXTENSIONS.joinToString(" / ") { ".$it" }
}
