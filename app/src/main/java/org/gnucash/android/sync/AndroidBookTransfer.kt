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

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import org.gnucash.android.export.ExportFormat
import org.gnucash.android.export.ExportParams
import org.gnucash.android.export.ExportTarget
import org.gnucash.android.export.xml.GncXmlExporter
import java.io.File
import java.io.IOException

/**
 * The two places where the remote-backup feature touches the device's own files.
 *
 * Both directions go through a **scratch file in the app cache**, and each has a reason that
 * matters more than it looks:
 *
 *  - **Export for upload** reuses the app's own XML exporter rather than a second serialiser,
 *    so the bytes that go to the cloud are byte-for-byte the same shape as the `.gnca` backup
 *    the user can already make locally — the same file the restore path is able to read.
 *    Exported **compressed** (gzip), which is also what [org.gnucash.android.util.BookPayload]
 *    recognises on the way back.
 *  - **Staging a restore** lands in the cache directory and is handed on as a `file://` [Uri],
 *    which is the form `ImportAsyncTask` already accepts for a local backup (see
 *    `BackupPreferenceFragment.restoreBackup`). Nothing is written into the book here: the
 *    importer opens a **new** book, so a failed restore cannot damage the current one.
 *
 * The staging file has a fixed name on purpose. Only one restore can be in flight from this
 * page, and a name carrying a timestamp would leave every earlier attempt behind in the cache
 * — a slow leak of the user's whole book, in the clear, including when the upload was
 * encrypted.
 */
object AndroidBookTransfer {

    private const val SCRATCH_FOLDER = "webdav"
    private const val UPLOAD_FILE = "upload.gnca"
    private const val RESTORE_FILE = "restore.gnca"

    /**
     * Reads the active book as the bytes of a compressed GnuCash XML backup.
     *
     * @throws IOException when the exporter produced nothing, so the caller can refuse the
     *         upload rather than send a zero-byte object over a good remote backup.
     */
    @WorkerThread
    fun exportBook(context: Context, bookUid: String): ByteArray {
        val params = ExportParams(ExportFormat.XML)
        params.exportTarget = ExportTarget.URI
        params.isCompressed = true

        val scratch = File(scratchFolder(context), UPLOAD_FILE)
        params.exportLocation = Uri.fromFile(scratch)

        val exported = GncXmlExporter(context, params, bookUid).export()
            ?: throw IOException("the exporter produced no file for book $bookUid")
        val file = File(exported.path ?: exported.toString())

        return try {
            file.readBytes()
        } finally {
            // The book in the clear must not linger in the cache. Deleted even when the read
            // failed: leaving it behind is worse than losing the diagnostics.
            file.delete()
        }
    }

    /** Writes a fetched book into the cache and returns the `file://` uri to import it from. */
    fun stageRestoredBook(context: Context, book: ByteArray): Uri {
        val file = File(scratchFolder(context), RESTORE_FILE)
        // Truncating on purpose: this file is at most one book, and appending a second one
        // would produce a payload that opens as garbage.
        file.outputStream().use { it.write(book) }
        return Uri.fromFile(file)
    }

    private fun scratchFolder(context: Context): File =
        File(context.cacheDir, SCRATCH_FOLDER).apply { mkdirs() }
}
