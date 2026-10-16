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

import org.gnucash.android.sync.webdav.IncompleteReason
import org.gnucash.android.sync.webdav.ListingResult
import org.gnucash.android.sync.webdav.WebDavActions

/**
 * Everything the settings page can be told about a **remote backup** attempt, as a value.
 *
 * ### Why an enum and not a boolean/toast
 *
 * The tempting shape for a settings page is `if (ok) "done" else "failed"`. Every distinct
 * outcome below has a **different next move for the user**, and collapsing them loses exactly
 * the information that makes the failure fixable:
 *
 *  - “the server refused us” (`401`/`403`) ⇒ fix the user name or password;
 *  - “we never reached the server” (`status` equal to
 *    [WebDavActions.STATUS_NO_RESPONSE]) ⇒ fix the
 *    address or the network — telling the user to check their password here sends them to the
 *    wrong field;
 *  - “there is no backup yet” ⇒ upload one, rather than re-typing credentials;
 *  - “the file is ours but unreadable” (corrupt, or a newer container) ⇒ it is on the remote,
 *    so do **not** overwrite it blindly;
 *  - “what came back is not a book at all” ⇒ question the **address** (a login page answered);
 *  - “the backup is encrypted and you gave no passphrase” ⇒ ask for the passphrase; this is
 *    **not** a failure and must never be reported as one — see [needsPassphrase].
 *
 * The mapping lives here, free of Android, so each edge can be asserted (see
 * `BackupNoticeTest`); the fragment only turns the enum into a string.
 */
enum class BackupNotice {

    /** Uploaded in the clear: the remote file is still openable by desktop GnuCash. */
    UPLOAD_STORED_PLAIN,

    /**
     * Uploaded as an [EncryptedBackup] container. The UI has to say out loud that desktop
     * GnuCash can no longer open this file (plan §8) — that is the price of this branch.
     */
    UPLOAD_STORED_ENCRYPTED,

    /** The server answered our upload with a refusal. */
    UPLOAD_REFUSED,

    /** The upload never reached the server. */
    UPLOAD_UNREACHABLE,

    /** The remote backup was fetched and is in the clear. */
    RESTORE_GOT_PLAIN,

    /** The remote backup was fetched and decrypted. */
    RESTORE_GOT_ENCRYPTED,

    /** The remote backup is encrypted and no passphrase was supplied: ask, then retry. */
    RESTORE_NEEDS_PASSPHRASE,

    /** A passphrase was supplied and does not open the backup. */
    RESTORE_WRONG_PASSPHRASE,

    /** There is no backup for this book on the remote. */
    RESTORE_NOTHING_THERE,

    /** The remote file is there but unusable: corrupt, or a container this build does not know. */
    RESTORE_UNREADABLE,

    /**
     * The remote answered `200` with something that is not a book — a login page, an empty
     * body, a directory listing. The address is the prime suspect, not the passphrase.
     */
    RESTORE_NOT_A_BOOK,

    /** The server refused the read. */
    RESTORE_REFUSED,

    /** The read never reached the server. */
    RESTORE_UNREACHABLE;

    /** `true` only for the four outcomes in which the requested thing actually happened. */
    val isSuccess: Boolean
        get() = this == UPLOAD_STORED_PLAIN || this == UPLOAD_STORED_ENCRYPTED ||
            this == RESTORE_GOT_PLAIN || this == RESTORE_GOT_ENCRYPTED

    /**
     * `true` when the user's next move is to **type a passphrase**, not to fix anything.
     *
     * Called out as its own property because it is the one non-success outcome the UI must
     * treat as a prompt: showing "restore failed" here tells the user their backup is broken
     * when it is in fact perfectly fine.
     */
    val needsPassphrase: Boolean
        get() = this == RESTORE_NEEDS_PASSPHRASE
}

/** What the connection test found. Distinct from [BackupNotice]: nothing was moved. */
enum class ConnectionNotice {

    /** The root answered a `207 Multi-Status` listing. The share is there and readable. */
    READY,

    /** The server was never reached: DNS, TLS, timeout. */
    UNREACHABLE,

    /** The server answered, but not with a `207`: usually the URL is not a WebDAV root. */
    NOT_A_WEBDAV_ROOT,

    /** The answer parsed as a `207` but carried a failure inside, or was not XML at all. */
    BAD_RESPONSE,

    /** The subtree was deeper than the recursion bound — almost always a wrong root. */
    TOO_DEEP
}

/**
 * Maps the sync layer's outcomes onto the enum the settings page renders.
 *
 * Free of Android dependencies (the enum carries no resource ids), so every branch here is
 * covered by a plain JVM test.
 */
object BackupNotices {

    /** [BackupUploadOutcome.Stored] carries whether the payload was encrypted; nothing else does. */
    fun upload(outcome: BackupUploadOutcome): BackupNotice = when (outcome) {
        is BackupUploadOutcome.Stored ->
            if (outcome.encrypted) BackupNotice.UPLOAD_STORED_ENCRYPTED
            else BackupNotice.UPLOAD_STORED_PLAIN

        // `status == 0` is the transport's own "no response" sentinel, not an HTTP code.
        // Folding it into "refused" would tell the user to check credentials they cannot fix.
        is BackupUploadOutcome.Failed ->
            if (outcome.status == WebDavActions.STATUS_NO_RESPONSE) BackupNotice.UPLOAD_UNREACHABLE
            else BackupNotice.UPLOAD_REFUSED
    }

    fun download(outcome: BackupDownloadOutcome): BackupNotice = when (outcome) {
        is BackupDownloadOutcome.Got ->
            if (outcome.encrypted) BackupNotice.RESTORE_GOT_ENCRYPTED
            else BackupNotice.RESTORE_GOT_PLAIN

        is BackupDownloadOutcome.PassphraseRequired -> BackupNotice.RESTORE_NEEDS_PASSPHRASE
        is BackupDownloadOutcome.WrongPassphrase -> BackupNotice.RESTORE_WRONG_PASSPHRASE
        is BackupDownloadOutcome.Missing -> BackupNotice.RESTORE_NOTHING_THERE
        is BackupDownloadOutcome.Unreadable -> BackupNotice.RESTORE_UNREADABLE
        is BackupDownloadOutcome.NotABook -> BackupNotice.RESTORE_NOT_A_BOOK

        is BackupDownloadOutcome.Failed ->
            if (outcome.status == WebDavActions.STATUS_NO_RESPONSE) BackupNotice.RESTORE_UNREACHABLE
            else BackupNotice.RESTORE_REFUSED
    }

    /**
     * Reads a listing as "can this address be used".
     *
     * The `Complete` branch is deliberately **not** "the listing was non-empty": an empty
     * WebDAV folder is a perfectly good sync root, and testing for entries would reject the
     * very first connection a new user makes.
     */
    fun connection(listing: ListingResult): ConnectionNotice = when (listing) {
        is ListingResult.Complete -> ConnectionNotice.READY
        is ListingResult.Incomplete -> when (listing.reason) {
            IncompleteReason.Transport -> ConnectionNotice.UNREACHABLE
            IncompleteReason.HttpStatus -> ConnectionNotice.NOT_A_WEBDAV_ROOT
            IncompleteReason.MalformedResponse,
            IncompleteReason.FailedResponse -> ConnectionNotice.BAD_RESPONSE
            IncompleteReason.DepthExceeded -> ConnectionNotice.TOO_DEEP
        }
    }
}
