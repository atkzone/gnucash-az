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

import org.gnucash.android.sync.webdav.ActionOutcome
import org.gnucash.android.sync.webdav.DownloadOutcome
import org.gnucash.android.sync.webdav.WebDavClient
import org.gnucash.android.util.BookPayload

/**
 * One-way **remote backup and restore** of a whole book (plan §5② step 2, feature ② v1).
 *
 * Unlike the v2 record merge ([SyncRound]), which moves one file per account or
 * transaction, v1 moves the book as a single file — the same gzipped GnuCash XML the app
 * already writes locally — and it moves it in **one direction at a time**: "put my book
 * up" or "bring the remote book down". There is no merge, so there is no baseline, no
 * tombstone and no deletion detection here.
 *
 * ### One fixed path per book, not one per mode
 *
 * The remote name is always `/backup/<bookUid>.gnca`, whether or not the payload is
 * encrypted; the container's own [EncryptedBackup.MAGIC] is what says which. Keeping a
 * single slot is deliberate:
 *
 *  - a second name ("`.gnca.enc`") would let an *upload that switches mode* leave the old
 *    file behind, and a later restore would then have to guess which of the two is
 *    current — the wrong guess restores a superseded book;
 *  - the extension is the file's **identity** to a human browsing the WebDAV folder, and
 *    to GnuCash; changing it per mode means the same book shows up as two different files.
 *
 * The cost is that an encrypted `/backup/<uid>.gnca` is not openable by desktop GnuCash —
 * which plan §8 already requires the UI to say out loud. The magic line is the mitigation
 * for anyone who opens it in a text editor: it is the first thing they see.
 *
 * ### The first guard: never hand back ciphertext
 *
 * [download] never returns a container's bytes as if they were a book. An encrypted
 * remote with no passphrase is [BackupDownloadOutcome.PassphraseRequired] — a refusal,
 * not "here are some bytes". Handing ciphertext to the importer would produce a durable,
 * silent failure ("the restore said it worked; the book is now garbage"), which is the
 * one outcome this whole feature exists to prevent.
 *
 * ### The second guard: a `200` is not a promise that the body is a book
 *
 * Once the payload is in the clear, [download] still refuses it unless it is actually one
 * of the containers a book arrives in — see [BookPayload]. A captive portal, a reverse
 * proxy in front of the share, an SSO login form, or a URL that happens to point at a web
 * root all answer `200` with a perfectly valid HTML page. The importer would eventually
 * reject that, but by then the restore has already written it over the local book. This
 * gate is what makes the failure loud *and* harmless, and it is reported as
 * [BackupDownloadOutcome.NotABook] rather than folded into [BackupDownloadOutcome.Failed]:
 * "the server refused" and "the server served me a login page" call for different fixes.
 *
 * Free of Android dependencies: it drives a [WebDavClient], which itself only needs a
 * [org.gnucash.android.sync.webdav.WebDavTransport]. See `RemoteBackupTest`.
 */
object RemoteBackup {

    /** Remote collection holding book backups. Distinct from every `SyncRecordType.id`. */
    const val FOLDER: String = "backup"

    /** Extension of a book backup, matching the local `.gnca` backups. */
    const val EXTENSION: String = "gnca"

    /**
     * The remote path a book's backup lives at — the same one for plain and encrypted
     * backups; see the class doc.
     *
     * @throws IllegalArgumentException when [bookUid] cannot be a path segment. It comes
     *         from our own database rather than the network, so a bad one is a programming
     *         error; throwing keeps it from producing a path that a later listing would
     *         silently not recognise.
     */
    fun pathFor(bookUid: String): String {
        require(bookUid.isNotBlank()) { "a book uid must not be blank" }
        require('/' !in bookUid) { "a book uid must not contain a path separator: $bookUid" }
        require(!bookUid.startsWith(".")) { "a book uid must not start with a dot: $bookUid" }
        return "/$FOLDER/$bookUid.$EXTENSION"
    }

    /**
     * Uploads [book] to [pathFor]`(bookUid)`.
     *
     * A null **or empty** [passphrase] means "no encryption": the bytes go up verbatim, so
     * the remote file stays openable by desktop GnuCash. Anything else wraps them in an
     * [EncryptedBackup] container.
     *
     * The `PUT` is unconditional on purpose. The conditional-write rule (§7.6) exists to
     * protect a *merge* from a peer that wrote the same record in between; here the slot
     * belongs to this device and "replace whatever is there" is the requested behaviour,
     * not a lost update.
     *
     * @param iterations PBKDF2 iterations, forwarded to [EncryptedBackup.encrypt]; tests
     *                   lower it so a whole class of round trips stays fast.
     */
    fun upload(
        client: WebDavClient,
        bookUid: String,
        book: ByteArray,
        passphrase: CharArray?,
        iterations: Int = EncryptedBackup.DEFAULT_ITERATIONS
    ): BackupUploadOutcome {
        // `CharArray` is not a `CharSequence`, so `isNullOrEmpty()` does not apply to it;
        // "no passphrase" is spelled out. An empty passphrase cannot be a key anyway
        // (`EncryptedBackup.encrypt` refuses one), so treating it as "none" keeps the two
        // entry points agreeing instead of failing one of them at random.
        val key = passphrase?.takeIf { it.isNotEmpty() }
        val payload = key?.let { EncryptedBackup.encrypt(book, it, iterations) } ?: book
        val encrypted = key != null
        return when (val outcome = client.putCreatingCollections(pathFor(bookUid), payload)) {
            is ActionOutcome.Done -> BackupUploadOutcome.Stored(encrypted, outcome.etag)
            // A `PUT` does not answer "already there" (that is `MKCOL`), but the outcome is
            // handled rather than assumed away: it still means the slot now holds our bytes.
            is ActionOutcome.AlreadyDone -> BackupUploadOutcome.Stored(encrypted, etag = null)
            is ActionOutcome.Stale -> BackupUploadOutcome.Failed(outcome.status, outcome.detail)
            is ActionOutcome.Missing -> BackupUploadOutcome.Failed(outcome.status, outcome.detail)
            is ActionOutcome.Failed -> BackupUploadOutcome.Failed(outcome.status, outcome.detail)
        }
    }

    /**
     * Fetches the book backed up for [bookUid] and returns it **in the clear**.
     *
     * A plain remote file is returned as it is, even when a [passphrase] was supplied
     * (the user may have a passphrase set and the remote may predate it). An encrypted one
     * requires a passphrase — see the class doc on the hard guard.
     */
    fun download(
        client: WebDavClient,
        bookUid: String,
        passphrase: CharArray?
    ): BackupDownloadOutcome = when (val got = client.download(pathFor(bookUid))) {
        is DownloadOutcome.Got -> open(got.bytes, passphrase)
        is DownloadOutcome.Refused -> when (val reason = got.reason) {
            is ActionOutcome.Missing -> BackupDownloadOutcome.Missing
            is ActionOutcome.Stale -> BackupDownloadOutcome.Failed(reason.status, reason.detail)
            is ActionOutcome.Failed -> BackupDownloadOutcome.Failed(reason.status, reason.detail)
            is ActionOutcome.Done -> BackupDownloadOutcome.Failed(reason.status, UNEXPECTED_GET)
            is ActionOutcome.AlreadyDone ->
                BackupDownloadOutcome.Failed(reason.status, UNEXPECTED_GET)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun open(bytes: ByteArray, passphrase: CharArray?): BackupDownloadOutcome {
        val encrypted = EncryptedBackup.isEncrypted(bytes)
        val clear = if (!encrypted) {
            bytes
        } else {
            // The whole point of the magic line: refuse here, so no caller can mistake
            // ciphertext for a book.
            val key = passphrase?.takeIf { it.isNotEmpty() }
                ?: return BackupDownloadOutcome.PassphraseRequired
            try {
                EncryptedBackup.decrypt(bytes, key)
            } catch (e: WrongPasswordException) {
                return BackupDownloadOutcome.WrongPassphrase
            } catch (e: BackupException) {
                // Malformed, or a format version this build does not know: the file is there
                // and is ours, it just cannot be opened. Distinct from "no backup" and from
                // "the server said no", because the user's next move differs for each.
                return BackupDownloadOutcome.Unreadable(e.message ?: "the backup could not be opened")
            }
        }

        // The 200-is-not-a-book gate. It runs on the clear bytes in both branches, because
        // "we uploaded something that was not a book" is just as restorable a mistake as
        // "the server served us a login page".
        return when (val verdict = BookPayload.inspect(clear)) {
            is BookPayload.Verdict.Acceptable -> BackupDownloadOutcome.Got(clear, encrypted)
            is BookPayload.Verdict.Rejected -> BackupDownloadOutcome.NotABook(verdict.detail)
        }
    }

    private const val UNEXPECTED_GET = "unexpected GET outcome"
}

/** Result of [RemoteBackup.upload]. */
sealed interface BackupUploadOutcome {

    /**
     * The slot now holds our bytes.
     *
     * @param encrypted whether those bytes are an [EncryptedBackup] container.
     * @param etag      the entity tag the server reported, when it sent one. v1 does not
     *                  use it for anything (there is no base to keep), but a caller that
     *                  later wants a conditional write has it.
     */
    data class Stored(val encrypted: Boolean, val etag: String?) : BackupUploadOutcome

    /** The upload did not land. [status] is `0` when the server was never reached. */
    data class Failed(val status: Int, val detail: String) : BackupUploadOutcome
}

/** Result of [RemoteBackup.download]. */
sealed interface BackupDownloadOutcome {

    /** The book, decryptable — i.e. ready to hand to the importer. */
    data class Got(val book: ByteArray, val encrypted: Boolean) : BackupDownloadOutcome

    /**
     * The remote backup is encrypted and no passphrase was supplied. **No bytes were
     * returned**; the caller must prompt and try again.
     */
    data object PassphraseRequired : BackupDownloadOutcome

    /** A passphrase was supplied but does not open the backup (or the file was altered). */
    data object WrongPassphrase : BackupDownloadOutcome

    /** There is no backup for this book on the remote. */
    data object Missing : BackupDownloadOutcome

    /** The remote file is there but unusable — corrupt, or a newer container format. */
    data class Unreadable(val detail: String) : BackupDownloadOutcome

    /**
     * The remote file was read but is not a GnuCash book at all — a web page, an empty
     * body, or something with no recognisable container signature.
     *
     * Kept apart from [Unreadable] ("ours, but corrupt or too new") and from [Failed]
     * ("the server refused") because the user's next move here is to question the
     * **address**: it is most likely pointing at a login page or the wrong folder rather
     * than at the WebDAV share. See [BookPayload] for what counts as a book.
     */
    data class NotABook(val detail: String) : BackupDownloadOutcome

    /** The remote could not be reached, or refused the read. */
    data class Failed(val status: Int, val detail: String) : BackupDownloadOutcome
}
