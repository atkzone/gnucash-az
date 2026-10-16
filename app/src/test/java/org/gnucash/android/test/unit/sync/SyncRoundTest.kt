package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.db.DatabaseSchema.SyncResolutionEntry
import org.gnucash.android.sync.LocalRecordStore
import org.gnucash.android.sync.RecordCodec
import org.gnucash.android.sync.RecordState
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncLayout
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.SyncRound
import org.gnucash.android.sync.SyncRoundResult
import org.gnucash.android.sync.webdav.DownloadResponse
import org.gnucash.android.sync.webdav.TransportResponse
import org.gnucash.android.sync.webdav.WebDavClient
import org.gnucash.android.sync.webdav.WebDavTransport
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * One whole round, wired to a real book database and an in-memory peer (plan §5②, §7).
 *
 * The pure planner is covered exhaustively by [SyncRoundPlannerTest]; this class is about
 * the **wiring** the planner cannot see: that the listing's etags really do drive "fetch or
 * not", that a conditional `PUT` really does carry the header the planner chose, and that
 * the base/tombstone/pending write-back lands in the database. The peer is a fake
 * transport, so every case is deterministic and no network is involved.
 */
class SyncRoundTest : GnuCashTest() {

    private lateinit var state: SyncStateDbAdapter
    private lateinit var accounts: AccountsDbAdapter
    private lateinit var store: InMemoryStore
    private lateinit var server: FakeServer
    private var clock = 1_000L

    @Before
    fun setUp() {
        state = SyncStateDbAdapter.instance
        accounts = AccountsDbAdapter.instance
        state.deleteAllRecords()
        accounts.holder.db.delete("tombstones", null, null)
        accounts.holder.db.delete(SyncResolutionEntry.TABLE_NAME, null, null)
        store = InMemoryStore()
        server = FakeServer()
        clock = 1_000L
    }

    @After
    fun tearDown() {
        state.deleteAllRecords()
        accounts.holder.db.delete("tombstones", null, null)
        accounts.holder.db.delete(SyncResolutionEntry.TABLE_NAME, null, null)
    }

    // --------------------------------------------------------------------- fixtures

    private class InMemoryStore(
        private val records: LinkedHashMap<SyncIdentity, Map<String, String>> = LinkedHashMap()
    ) : LocalRecordStore {

        override val supportedTypes: Set<SyncRecordType> = SyncRecordType.entries.toSet()

        /** Set to have the store decline every write, the way a store whose dependency is
         *  not in the book yet does. */
        var refuseWrites = false

        override fun list(types: Collection<SyncRecordType>): List<RecordState> =
            records.map { (id, fields) -> RecordState(id.recordType, id.key, fields) }
                .filter { it.type in types }

        override fun write(state: RecordState): Boolean {
            if (refuseWrites) return false
            records[SyncIdentity(state.type, state.key)] = state.fields
            return true
        }

        override fun delete(identity: SyncIdentity): Boolean = records.remove(identity) != null

        fun fields(key: String) = records[SyncIdentity(SyncRecordType.TRANSACTION, key)]
        fun has(key: String) = records.containsKey(SyncIdentity(SyncRecordType.TRANSACTION, key))
        fun set(key: String, fields: Map<String, String>) {
            records[SyncIdentity(SyncRecordType.TRANSACTION, key)] = fields
        }
    }

    /** A minimal in-memory WebDAV server: a flat `/&lt;type&gt;/&lt;key&gt;.gncs` namespace under a base path. */
    private class FakeServer {

        private val base = "https://dav.example/remote.php/dav/files/u/book"
        private val prefix = "/remote.php/dav/files/u/book"

        private val files = LinkedHashMap<String, ByteArray>()
        private val etags = LinkedHashMap<String, String>()
        private val dirs = mutableSetOf<String>()
        private var counter = 0

        var propfindShouldFail = false
        var force412 = false
        var getCount = 0
        val putRequests = mutableListOf<PutRequest>()

        data class PutRequest(val path: String, val headers: Map<String, String>, val body: ByteArray)

        fun client() = WebDavClient(base, transport())

        fun listPaths(): Set<String> = files.keys.toSet()
        fun content(path: String): String = String(files[path] ?: ByteArray(0), Charsets.UTF_8)
        fun etagOf(path: String): String? = etags[path]

        /** A peer-side write (a human editing the file on the other device). */
        fun write(path: String, text: String) {
            files[path] = text.toByteArray(Charsets.UTF_8)
            etags[path] = "e${++counter}"
            dirs += path.substringBeforeLast('/')
        }

        fun remove(path: String) {
            files.remove(path)
            etags.remove(path)
        }

        private fun transport(): WebDavTransport = object : WebDavTransport {
            override fun propfind(url: String, depth: Int, body: String): TransportResponse {
                if (propfindShouldFail) throw IOException("connection reset")
                return TransportResponse(207, listingXml(relative(url)))
            }

            override fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?
            ): TransportResponse {
                val path = relative(url)
                return when (method) {
                    "MKCOL" -> if (dirs.add(path)) TransportResponse(201, "") else TransportResponse(405, "")
                    "PUT" -> {
                        putRequests += PutRequest(path, headers, body ?: ByteArray(0))
                        val current = etags[path]
                        if (force412) return TransportResponse(412, "")
                        if (headers["If-None-Match"] == "*" && current != null) {
                            return TransportResponse(412, "")
                        }
                        headers["If-Match"]?.let { if (it != "\"$current\"") return TransportResponse(412, "") }
                        val etag = "e${++counter}"
                        files[path] = body ?: ByteArray(0)
                        etags[path] = etag
                        dirs += path.substringBeforeLast('/')
                        return TransportResponse(201, "", etag)
                    }
                    "DELETE" -> if (files.remove(path) != null) {
                        etags.remove(path)
                        TransportResponse(204, "")
                    } else {
                        TransportResponse(404, "")
                    }
                    else -> TransportResponse(405, "")
                }
            }

            override fun download(url: String, headers: Map<String, String>): DownloadResponse {
                getCount++
                val path = relative(url)
                val bytes = files[path] ?: return DownloadResponse(404, ByteArray(0), null)
                return DownloadResponse(200, bytes, etags[path])
            }
        }

        private fun relative(url: String): String = url.removePrefix(base).ifEmpty { "/" }

        private fun listingXml(collection: String): String {
            val xml = StringBuilder()
            xml.append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
            xml.append(entry("$prefix$collection", collection = true, etag = null))
            if (collection == "/") {
                val segments = files.keys.map { it.trimStart('/').substringBefore('/') }.toSortedSet()
                for (segment in segments) {
                    if (segment.isNotEmpty()) xml.append(entry("$prefix/$segment", true, null))
                }
            } else {
                val dir = collection.trimStart('/')
                for ((path, _) in files) {
                    if (path.startsWith("/$dir/")) xml.append(entry("$prefix$path", false, etags[path]))
                }
            }
            xml.append("</D:multistatus>")
            return xml.toString()
        }

        private fun entry(href: String, collection: Boolean, etag: String?): String {
            val type = if (collection) "<D:collection/>" else ""
            val etagXml = etag?.let { "<D:getetag>$it</D:getetag>" } ?: ""
            return "<D:response><D:href>$href</D:href><D:propstat><D:prop>" +
                "<D:resourcetype>$type</D:resourcetype>$etagXml</D:prop>" +
                "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        }
    }

    private fun round(): SyncRoundResult {
        clock += 100
        return SyncRound(
            client = server.client(),
            state = state,
            store = store,
            now = { clock },
            deviceId = "test-device"
        ).run()
    }

    private fun path(key: String) = SyncLayout.pathFor(SyncRecordType.TRANSACTION, key)

    // -------------------------------------------------------------------- the cases

    @Test
    fun `a first round uploads every local record and records a base with the peer etag`() {
        store.set("t1", mapOf("description" to "one"))
        store.set("t2", mapOf("description" to "two"))

        val result = round()

        assertThat(result.completed).isTrue()
        assertThat(result.uploaded).isEqualTo(2)
        assertThat(result.downloaded).isZero()
        assertThat(server.listPaths()).containsExactlyInAnyOrder(path("t1"), path("t2"))

        // Creates, not updates: every write is conditional on the slot being empty.
        assertThat(server.putRequests).hasSize(2)
        assertThat(server.putRequests).allSatisfy {
            assertThat(it.headers["If-None-Match"]).isEqualTo("*")
        }

        val base = state.getBase(SyncRecordType.TRANSACTION, "t1")
        assertThat(base?.etag).isEqualTo(server.etagOf(path("t1")))
        assertThat(RecordCodec.decode(base!!.payload)).isEqualTo(mapOf("description" to "one"))
    }

    @Test
    fun `a second round with nothing changed reads nothing and writes nothing`() {
        store.set("t1", mapOf("a" to "1"))
        store.set("t2", mapOf("a" to "2"))
        round()
        server.putRequests.clear()
        val getsBefore = server.getCount

        val result = round()

        assertThat(result.uploaded).isZero()
        assertThat(result.downloaded).isZero()
        assertThat(server.putRequests).isEmpty()
        // The etag still matches the base, so no content is fetched at all.
        assertThat(server.getCount).isEqualTo(getsBefore)
    }

    @Test
    fun `a peer edit is pulled into the local store`() {
        store.set("t1", mapOf("a" to "1"))
        round()
        server.write(path("t1"), RecordCodec.encode(mapOf("a" to "9")))

        val result = round()

        assertThat(result.downloaded).isEqualTo(1)
        assertThat(store.fields("t1")).isEqualTo(mapOf("a" to "9"))
        assertThat(state.getBase(SyncRecordType.TRANSACTION, "t1")?.etag).isEqualTo(server.etagOf(path("t1")))
    }

    @Test
    fun `a local edit is pushed under a conditional header`() {
        store.set("t1", mapOf("a" to "1"))
        round()
        val etagBefore = server.etagOf(path("t1"))
        store.set("t1", mapOf("a" to "7"))
        server.putRequests.clear()

        val result = round()

        assertThat(result.uploaded).isEqualTo(1)
        assertThat(server.content(path("t1"))).isEqualTo(RecordCodec.encode(mapOf("a" to "7")))
        val write = server.putRequests.first { it.path == path("t1") }
        assertThat(write.headers["If-Match"]).isEqualTo("\"$etagBefore\"")
    }

    @Test
    fun `a conflicting edit is reported and neither side is overwritten`() {
        store.set("t1", mapOf("a" to "1"))
        round()
        server.write(path("t1"), RecordCodec.encode(mapOf("a" to "9")))
        store.set("t1", mapOf("a" to "7"))

        val result = round()

        assertThat(result.conflicts).isEqualTo(1)
        assertThat(result.uploaded).isZero()
        assertThat(result.downloaded).isZero()
        assertThat(store.fields("t1")).isEqualTo(mapOf("a" to "7"))
        assertThat(server.content(path("t1"))).isEqualTo(RecordCodec.encode(mapOf("a" to "9")))
    }

    @Test
    fun `a peer deletion is watched once, then adopted on the next round`() {
        store.set("t1", mapOf("a" to "1"))
        store.set("t2", mapOf("a" to "2"))
        round()
        server.remove(path("t1"))

        // Round two: the absence is seen for the first time — nothing is deleted.
        val first = round()
        assertThat(first.completed).isTrue()
        assertThat(store.has("t1")).isTrue()
        assertThat(state.getBase(SyncRecordType.TRANSACTION, "t1")?.pendingSince).isNotNull()
        assertThat(state.tombstoneCount()).isZero()

        // Round three: a second consecutive absence confirms it.
        val second = round()
        assertThat(second.localDeleted).isEqualTo(1)
        assertThat(store.has("t1")).isFalse()
        assertThat(state.getBase(SyncRecordType.TRANSACTION, "t1")).isNull()
        assertThat(state.tombstoneCount()).isEqualTo(1)
    }

    @Test
    fun `an unusable listing abandons the round and changes nothing`() {
        store.set("t1", mapOf("a" to "1"))
        val first = round()
        val etagBefore = state.getBase(SyncRecordType.TRANSACTION, "t1")?.etag
        store.set("t1", mapOf("a" to "7"))

        server.propfindShouldFail = true
        val result = round()

        assertThat(result.completed).isFalse()
        assertThat(result.abandoned).isTrue()
        // The local edit is neither pushed nor lost, and the base is untouched.
        assertThat(store.fields("t1")).isEqualTo(mapOf("a" to "7"))
        assertThat(server.content(path("t1"))).isEqualTo(RecordCodec.encode(mapOf("a" to "1")))
        assertThat(state.getBase(SyncRecordType.TRANSACTION, "t1")?.etag).isEqualTo(etagBefore)
        assertThat(first.completed).isTrue()
    }

    @Test
    fun `a precondition rejected by the peer stops the round instead of retrying it`() {
        store.set("t1", mapOf("a" to "1"))
        round()
        store.set("t1", mapOf("a" to "7"))

        server.force412 = true
        val result = round()

        assertThat(result.restartNeeded).isTrue()
        assertThat(result.uploaded).isZero()
    }

    @Test
    fun `a declined pull is not re-based, and the peer's copy survives the next rounds`() {
        // The record exists only on the peer: this device has never had it.
        server.write(path("t9"), RecordCodec.encode(mapOf("a" to "9")))
        store.refuseWrites = true

        val first = round()

        assertThat(first.deferred).isEqualTo(1)
        assertThat(first.downloaded).isZero()
        assertThat(store.has("t9")).isFalse()
        // The base must stay absent. Recording one here is the whole bug: the next round
        // would read "absent locally, present in the base, still on the peer" as a local
        // deletion and delete the peer's copy.
        assertThat(state.getBase(SyncRecordType.TRANSACTION, "t9")).isNull()

        val second = round()

        assertThat(second.deferred).isEqualTo(1)
        assertThat(server.listPaths()).contains(path("t9"))
        assertThat(store.has("t9")).isFalse()

        // Once the store can apply it, the round converges with no extra bookkeeping.
        store.refuseWrites = false
        val third = round()

        assertThat(third.downloaded).isEqualTo(1)
        assertThat(third.deferred).isZero()
        assertThat(store.fields("t9")).isEqualTo(mapOf("a" to "9"))
        assertThat(state.getBase(SyncRecordType.TRANSACTION, "t9")?.etag).isEqualTo(server.etagOf(path("t9")))
    }

    // ------------------------------------------------------- the user's answers (② v3)

    /** Drives the book into a genuine two-sided conflict on `t1` and leaves it parked. */
    private fun parkConflict() {
        store.set("t1", mapOf("a" to "1"))
        round()
        server.write(path("t1"), RecordCodec.encode(mapOf("a" to "9")))
        store.set("t1", mapOf("a" to "7"))
        assertThat(round().conflicts).isEqualTo(1)
    }

    @Test
    fun `a stored answer settles the conflict on the next round, and is then spent`() {
        parkConflict()

        state.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL)
        val resolved = round()

        assertThat(resolved.conflicts).isZero()
        assertThat(resolved.uploaded).isEqualTo(1)
        assertThat(server.content(path("t1"))).isEqualTo(RecordCodec.encode(mapOf("a" to "7")))
        // Spent: the question was asked once and answered once.
        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1")).isNull()
        assertThat(state.resolutionCount()).isZero()
    }

    @Test
    fun `taking the peer's version brings it here, and is then spent`() {
        parkConflict()

        state.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.REMOTE)
        val resolved = round()

        assertThat(resolved.conflicts).isZero()
        assertThat(resolved.downloaded).isEqualTo(1)
        assertThat(store.fields("t1")).isEqualTo(mapOf("a" to "9"))
        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1")).isNull()
    }

    @Test
    fun `a rejected forced write keeps the answer for the next attempt`() {
        parkConflict()
        state.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.LOCAL)

        server.force412 = true
        val rejected = round()

        assertThat(rejected.restartNeeded).isTrue()
        assertThat(rejected.uploaded).isZero()
        // The choice must survive: dropping it here would re-park a conflict the user settled,
        // and a `412` is precisely the "the peer moved, try again" case the override is for.
        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1")).isEqualTo(ResolveSide.LOCAL)
    }

    @Test
    fun `a declined forced pull keeps the answer for the next attempt`() {
        parkConflict()
        state.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.REMOTE)

        store.refuseWrites = true
        val declined = round()

        assertThat(declined.deferred).isEqualTo(1)
        assertThat(declined.downloaded).isZero()
        // The record needed something not in the book yet; the answer waits rather than being
        // silently discarded, so the round where it becomes applicable finally honours it.
        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1")).isEqualTo(ResolveSide.REMOTE)

        store.refuseWrites = false
        val honoured = round()

        assertThat(honoured.downloaded).isEqualTo(1)
        assertThat(store.fields("t1")).isEqualTo(mapOf("a" to "9"))
        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1")).isNull()
    }

    @Test
    fun `an answer for a record that stopped conflicting is dropped, not left to resurface`() {
        store.set("t1", mapOf("a" to "1"))
        round()
        // The two sides still agree, so nothing is parked — the answer names a conflict that no
        // longer exists and must not be carried forward to force a write later.
        state.putResolution(SyncRecordType.TRANSACTION, "t1", ResolveSide.REMOTE)

        val result = round()

        assertThat(result.conflicts).isZero()
        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1")).isNull()
        assertThat(state.resolutionCount()).isZero()
    }
}
