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
import org.gnucash.android.sync.BackupNotice
import org.gnucash.android.sync.ConnectionNotice
import org.gnucash.android.sync.PassphraseDecision
import org.gnucash.android.sync.webdav.WebDavConnection

/**
 * The one place that turns a typed outcome into words.
 *
 * The sync layer deliberately carries no resource ids (so it stays testable as plain JVM), and
 * this file is the other half of that split. Two things it must **not** do:
 *
 *  - **collapse two outcomes onto one message.** Every branch below has its own string, and
 *    `WebDavNoticeTextTest` asserts the ids are pairwise distinct — a copy-pasted branch is the
 *    one mistake an exhaustive `when` cannot catch;
 *  - **turn a prompt into an error.** [BackupNotice.RESTORE_NEEDS_PASSPHRASE] exists so the UI
 *    can ask for a passphrase instead of reporting that the user's backup is broken.
 *
 * [preferenceKeyOf] pairs a validation problem with the row it is about, so the page can mark
 * the offending field instead of showing a page-level error the user has to guess at.
 */
object WebDavNoticeText {

    @StringRes
    fun of(notice: BackupNotice): Int = when (notice) {
        BackupNotice.UPLOAD_STORED_PLAIN -> R.string.webdav_upload_stored_plain
        BackupNotice.UPLOAD_STORED_ENCRYPTED -> R.string.webdav_upload_stored_encrypted
        BackupNotice.UPLOAD_REFUSED -> R.string.webdav_upload_refused
        BackupNotice.UPLOAD_UNREACHABLE -> R.string.webdav_upload_unreachable
        BackupNotice.RESTORE_GOT_PLAIN -> R.string.webdav_restore_got_plain
        BackupNotice.RESTORE_GOT_ENCRYPTED -> R.string.webdav_restore_got_encrypted
        BackupNotice.RESTORE_NEEDS_PASSPHRASE -> R.string.webdav_restore_needs_passphrase
        BackupNotice.RESTORE_WRONG_PASSPHRASE -> R.string.webdav_restore_wrong_passphrase
        BackupNotice.RESTORE_NOTHING_THERE -> R.string.webdav_restore_nothing_there
        BackupNotice.RESTORE_UNREADABLE -> R.string.webdav_restore_unreadable
        BackupNotice.RESTORE_NOT_A_BOOK -> R.string.webdav_restore_not_a_book
        BackupNotice.RESTORE_REFUSED -> R.string.webdav_restore_refused
        BackupNotice.RESTORE_UNREACHABLE -> R.string.webdav_restore_unreachable
    }

    @StringRes
    fun of(notice: ConnectionNotice): Int = when (notice) {
        ConnectionNotice.READY -> R.string.webdav_connection_ready
        ConnectionNotice.UNREACHABLE -> R.string.webdav_connection_unreachable
        ConnectionNotice.NOT_A_WEBDAV_ROOT -> R.string.webdav_connection_not_a_webdav_root
        ConnectionNotice.BAD_RESPONSE -> R.string.webdav_connection_bad_response
        ConnectionNotice.TOO_DEEP -> R.string.webdav_connection_too_deep
    }

    /**
     * The problem in words.
     *
     * The `WebDavConnection` detail (`"the address needs an http:// or https:// prefix"`) is
     * deliberately not shown: it names the same thing in a language the user did not ask for,
     * and the typed enum already says which of the eleven things went wrong.
     */
    @StringRes
    fun of(problem: WebDavConnection.Problem): Int = when (problem) {
        WebDavConnection.Problem.BLANK -> R.string.webdav_problem_blank
        WebDavConnection.Problem.MISSING_SCHEME -> R.string.webdav_problem_missing_scheme
        WebDavConnection.Problem.UNSUPPORTED_SCHEME -> R.string.webdav_problem_unsupported_scheme
        WebDavConnection.Problem.MISSING_HOST -> R.string.webdav_problem_missing_host
        WebDavConnection.Problem.CREDENTIALS_IN_URL -> R.string.webdav_problem_credentials_in_url
        WebDavConnection.Problem.QUERY_OR_FRAGMENT -> R.string.webdav_problem_query_or_fragment
        WebDavConnection.Problem.UNESCAPED_CHARACTER -> R.string.webdav_problem_unescaped_character
        WebDavConnection.Problem.ENCODED_SEPARATOR -> R.string.webdav_problem_encoded_separator
        WebDavConnection.Problem.RELATIVE_SEGMENT -> R.string.webdav_problem_relative_segment
        WebDavConnection.Problem.PLAINTEXT -> R.string.webdav_problem_plaintext
        WebDavConnection.Problem.PASSWORD_WITHOUT_USER -> R.string.webdav_problem_password_without_user
    }

    @StringRes
    fun of(problem: PassphraseDecision.Problem): Int = when (problem) {
        PassphraseDecision.Problem.CONFIRMATION_WITHOUT_PASSPHRASE ->
            R.string.webdav_passphrase_problem_confirmation_without_passphrase

        PassphraseDecision.Problem.CONFIRMATION_MISMATCH ->
            R.string.webdav_passphrase_problem_mismatch

        PassphraseDecision.Problem.ACKNOWLEDGEMENT_MISSING ->
            R.string.webdav_passphrase_problem_acknowledgement_missing
    }

    /**
     * The preference row a validation problem is about, or `null` when it is about none.
     *
     * Nine of the eleven problems are one bad address, so they all land on the address row —
     * which is the point: the user has exactly one place to fix, and the page should say so.
     */
    @StringRes
    fun preferenceKeyOf(problem: WebDavConnection.Problem): Int? = when (problem) {
        WebDavConnection.Problem.BLANK,
        WebDavConnection.Problem.MISSING_SCHEME,
        WebDavConnection.Problem.UNSUPPORTED_SCHEME,
        WebDavConnection.Problem.MISSING_HOST,
        WebDavConnection.Problem.CREDENTIALS_IN_URL,
        WebDavConnection.Problem.QUERY_OR_FRAGMENT,
        WebDavConnection.Problem.UNESCAPED_CHARACTER,
        WebDavConnection.Problem.ENCODED_SEPARATOR,
        WebDavConnection.Problem.RELATIVE_SEGMENT -> R.string.key_webdav_server_url

        WebDavConnection.Problem.PLAINTEXT -> R.string.key_webdav_allow_plaintext
        WebDavConnection.Problem.PASSWORD_WITHOUT_USER -> R.string.key_webdav_username
    }
}
