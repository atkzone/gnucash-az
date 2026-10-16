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

import org.gnucash.android.sync.webdav.ListingResult
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.sync.webdav.WebDavTransportFactory
import timber.log.Timber

/** What one settings-page action produced. */
sealed interface WebDavSyncResult {

    /**
     * The typed fields are unusable, so **no request was made at all**.
     *
     * The settings page renders [problem] against the offending field ([detail] names it in
     * words). Kept apart from [Failed] because nothing was attempted: reporting this as a
     * network failure would send the user to debug a server that was never contacted.
     */
    data class NotUsable(
        val problem: WebDavConnection.Problem,
        val detail: String
    ) : WebDavSyncResult

    /** The connection test finished. [notice] says what the address turned out to be. */
    data class Checked(
        val notice: ConnectionNotice,
        val detail: String? = null
    ) : WebDavSyncResult

    /** The book is now on the remote. */
    data class Uploaded(val notice: BackupNotice) : WebDavSyncResult

    /**
     * The remote book came back **in the clear** and is ready to be handed to the importer.
     *
     * The bytes are only ever present on the success path: every refusal — encrypted with no
     * passphrase, wrong passphrase, not a book — arrives as [Failed] with no payload, so a
     * caller that writes "whatever it got" cannot write ciphertext over a good local book.
     */
    data class Downloaded(
        val book: ByteArray,
        val notice: BackupNotice
    ) : WebDavSyncResult

    /** The action did not happen. [detail] is the layer's own words, for the log. */
    data class Failed(
        val notice: BackupNotice,
        val detail: String? = null
    ) : WebDavSyncResult

    /** Reading the local book failed, so nothing was (or could be) sent. */
    data class LocalExportFailed(val detail: String) : WebDavSyncResult
}

/**
 * Drives one remote-backup action against a **checked** connection.
 *
 * ### What this class is for
 *
 * The pure layers below it ([RemoteBackup], [EncryptedBackup], [org.gnucash.android.sync.webdav.WebDavConnection])
 * each answer a narrow question. The settings page's real job is the *order* those answers
 * have to be asked in, and there are three orderings here that are easy to get wrong and
 * produce no error when they are:
 *
 *  1. **Validate before dialling.** A connection whose fields are invalid must not produce a
 *     single request. Otherwise "the address is missing `https://`" is reported as a network
 *     error and the user goes hunting on the server side. Every method below funnels through
 *     [checkedFirst], which is the one place that decides this.
 *  2. **Export before uploading, and never upload nothing.** An exporter that fails quietly
 *     would otherwise `PUT` a zero-byte object over a good backup — the remote copy is gone
 *     and the upload reported success. [backup] refuses an empty export.
 *  3. **Download, then decide, then write.** [restore] hands the payload back only on
 *     [WebDavSyncResult.Downloaded]; the caller writes it to the book afterwards. The guard
 *     that a `200` is not necessarily a book already runs inside [RemoteBackup.download], and
 *     this class is careful not to invert that order.
 *
 * Free of Android dependencies: the transport arrives as a [WebDavTransportFactory] and the
 * local book as a lambda, so the whole class is asserted with a fake transport and a fake
 * exporter (see `WebDavSyncControllerTest`).
 *
 * @param bookUid        uid of the book being backed up. Determines the remote path and the
 *                       export.
 * @param transportFactory builds the platform transport from the **checked** credentials.
 * @param exportBook     produces the local book's bytes. Called on the calling thread, so an
 *                       Android implementation has to be invoked off the main thread; it may
 *                       throw, and any failure is reported as [WebDavSyncResult.LocalExportFailed]
 *                       rather than escaping into the UI.
 */
class WebDavSyncController(
    private val bookUid: String,
    private val transportFactory: WebDavTransportFactory,
    private val exportBook: () -> ByteArray
) {

    /**
     * Asks the remote whether this address is a usable WebDAV root.
     *
     * A `207` listing is the cheapest honest proof: it exercises the URL, the credentials and
     * the server's WebDAV support in one request, and it distinguishes "wrong password" from
     * "this is a web page" from "nobody is home" — see [ConnectionNotice].
     */
    fun testConnection(connection: WebDavConnection): WebDavSyncResult =
        checkedFirst(connection) { ready ->
            when (val listing = ready.connect(transportFactory).listRecursive()) {
                is ListingResult.Complete ->
                    WebDavSyncResult.Checked(BackupNotices.connection(listing))

                is ListingResult.Incomplete ->
                    // The listing's own words, not a status code: they are what tells the user
                    // whether the URL is wrong or the server is merely slow.
                    WebDavSyncResult.Checked(BackupNotices.connection(listing), listing.detail)
            }
        }

    /**
     * Uploads the local book to the remote slot.
     *
     * @param passphrase `null` (or empty) means **no encryption**: the remote file stays
     *        openable by desktop GnuCash. A non-empty one wraps the payload in an
     *        [EncryptedBackup] container, at the cost of that openability — which is what the
     *        UI's irreversible warning is about.
     */
    fun backup(connection: WebDavConnection, passphrase: CharArray?): WebDavSyncResult =
        checkedFirst(connection) { ready ->
            val book = try {
                exportBook()
            } catch (e: Throwable) {
                Timber.e(e, "Could not read the local book for upload")
                return@checkedFirst WebDavSyncResult.LocalExportFailed(
                    e.message ?: "the local book could not be read"
                )
            }
            // A zero-byte PUT is not a harmless no-op: it replaces a good remote backup with
            // nothing, and every layer above would report success. Refuse instead.
            if (book.isEmpty()) {
                Timber.e("Refusing to upload: the local book exported to zero bytes")
                return@checkedFirst WebDavSyncResult.LocalExportFailed(
                    "the local book exported to zero bytes"
                )
            }

            val outcome = RemoteBackup.upload(
                ready.connect(transportFactory),
                bookUid,
                book,
                passphrase
            )
            when (outcome) {
                is BackupUploadOutcome.Stored -> WebDavSyncResult.Uploaded(BackupNotices.upload(outcome))
                is BackupUploadOutcome.Failed -> WebDavSyncResult.Failed(
                    BackupNotices.upload(outcome),
                    outcome.detail
                )
            }
        }

    /**
     * Fetches the remote backup and returns it for the caller to hand to the importer.
     *
     * Never returns bytes unless they are a book in the clear — an encrypted slot with no
     * passphrase comes back as [WebDavSyncResult.Failed] carrying
     * [BackupNotice.RESTORE_NEEDS_PASSPHRASE], which the UI presents as "type the passphrase",
     * not as an error.
     */
    fun restore(connection: WebDavConnection, passphrase: CharArray?): WebDavSyncResult =
        checkedFirst(connection) { ready ->
            val outcome = RemoteBackup.download(ready.connect(transportFactory), bookUid, passphrase)
            when (outcome) {
                is BackupDownloadOutcome.Got ->
                    WebDavSyncResult.Downloaded(outcome.book, BackupNotices.download(outcome))

                is BackupDownloadOutcome.PassphraseRequired ->
                    WebDavSyncResult.Failed(BackupNotice.RESTORE_NEEDS_PASSPHRASE)

                is BackupDownloadOutcome.WrongPassphrase ->
                    WebDavSyncResult.Failed(BackupNotice.RESTORE_WRONG_PASSPHRASE)

                is BackupDownloadOutcome.Missing ->
                    WebDavSyncResult.Failed(BackupNotice.RESTORE_NOTHING_THERE)

                is BackupDownloadOutcome.Unreadable ->
                    WebDavSyncResult.Failed(BackupNotice.RESTORE_UNREADABLE, outcome.detail)

                is BackupDownloadOutcome.NotABook ->
                    WebDavSyncResult.Failed(BackupNotice.RESTORE_NOT_A_BOOK, outcome.detail)

                is BackupDownloadOutcome.Failed ->
                    WebDavSyncResult.Failed(BackupNotices.download(outcome), outcome.detail)
            }
        }

    /**
     * The one place that decides "the fields are usable, now touch the network".
     *
     * Kept as a single funnel on purpose: the invariant worth protecting — *an invalid
     * connection produces zero requests* — is then true by construction rather than by three
     * separate call sites remembering to check first.
     */
    private inline fun checkedFirst(
        connection: WebDavConnection,
        action: (WebDavConnection.Check.Ready) -> WebDavSyncResult
    ): WebDavSyncResult = when (val check = connection.check()) {
        is WebDavConnection.Check.Invalid -> WebDavSyncResult.NotUsable(check.problem, check.detail)
        is WebDavConnection.Check.Ready -> action(check)
    }
}
