package org.gnucash.android.importer

import android.content.Context
import org.gnucash.android.gnc.GncProgressListener
import org.gnucash.android.importer.sql.SqliteImporter
import org.gnucash.android.importer.xml.GncXmlImporter
import org.gnucash.android.util.BookPayload
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object ImporterFactory {

    @Throws(IOException::class)
    fun create(
        context: Context,
        inputStream: InputStream,
        listener: GncProgressListener?
    ): Importer {
        val input = getInputStream(inputStream)
        val bis = input as? BufferedInputStream ?: BufferedInputStream(input)
        bis.mark(16)
        val header = ByteArray(16)
        val read = bis.read(header)
        bis.reset() //push back the header to the stream

        if (BookPayload.classify(header.copyOf(read)) == BookPayload.Kind.SQLITE) {
            return SqliteImporter(context, bis, listener)
        }
        return GncXmlImporter(context, bis, listener)
    }

    @Throws(IOException::class)
    fun getInputStream(inputStream: InputStream): InputStream {
        val bis = BufferedInputStream(inputStream)
        bis.mark(16)
        val header = ByteArray(16)
        val read = bis.read(header)
        if (read < 4) throw EOFException("file too small")
        bis.reset() //push back the signature to the stream

        // The container decision lives in `BookPayload` — one sniffer for the whole app,
        // shared with the remote restore path, which uses it to refuse a payload that is not
        // a book. Two copies of these magic numbers would drift apart the first time one of
        // them learned a new container.
        return when (BookPayload.classify(header.copyOf(read))) {
            BookPayload.Kind.SQLITE -> bis
            //check if matches standard gzip magic number
            BookPayload.Kind.GZIP -> GZIPInputStream(bis)
            BookPayload.Kind.ZIP -> {
                val zis = ZipInputStream(bis)
                if (zis.nextEntry != null) zis else bis
            }
            //XML and anything unrecognised go to the XML importer, which is what this has
            //always done: it fails loudly on a stream that is neither XML nor a database.
            else -> bis
        }
    }
}
