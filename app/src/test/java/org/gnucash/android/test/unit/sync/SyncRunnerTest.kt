package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.sync.LocalRecordStore
import org.gnucash.android.sync.RecordState
import org.gnucash.android.sync.SyncAbandon
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncNotice
import org.gnucash.android.sync.SyncOutcome
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.sync.SyncRunner
import org.gnucash.android.sync.webdav.DownloadResponse
import org.gnucash.android.sync.webdav.TransportResponse
import org.gnucash.android.sync.webdav.WebDavConnection
import org.gnucash.android.sync.webdav.WebDavTransport
import org.gnucash.android.sync.webdav.WebDavTransportFactory
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The wiring around one round: the settings page's fields becoming a round, and a round becoming
 * something the user is told.
 *
 * [SyncRoundTest] covers the round's own behaviour against a real book database; what is new here
 * is the seam above it — that an unusable connection costs **zero requests**, that a crash is
 * carried out to the screen instead of escaping into a background thread, and that this
 * installation's device id reaches the tombstones the round writes.
 */
class SyncRunnerTest : GnuCashTest() {

    private lateinit var state: SyncStateDbAdapter
    private lateinit var store: FakeStore
    private lateinit var transport: CountingTransport
    private var clock = 1_000L

    @Before
    fun setUp() {
        state = SyncStateDbAdapter.instance
        state.deleteAllRecords()
        AccountsDbAdapter.instance.holder.db.delete("tombstones", null, null)
        store = FakeStore()
        transport = CountingTransport()
        clock = 1_000L
    }

    @After
    fun tearDown() {
        state.deleteAllRecords()
        AccountsDbAdapter.instance.holder.db.delete("tombstones", null, null)
    }

    // --------------------------------------------------------------------- fixtures

    private class FakeStore(
        private val records: LinkedHashMap<SyncIdentity, Map<String, String>> = LinkedHashMap()
    ) : LocalRecordStore {

        override val supportedTypes: Set<SyncRecordType> =
            setOf(SyncRecordType.ACCOUNT, SyncRecordType.TRANSACTION)

        var throwOnList = false

        override fun list(types: Collection<SyncRecordType>): List<RecordState> {
            if (throwOnList) throw IllegalStateException("the book database is not readable")
            return records.map { (id, fields) -> RecordState(id.recordType, id.key, fields) }
                .filter { it.type in types }
        }

        override fun write(state: RecordState): Boolean {
            records[SyncIdentity(state.type, state.key)] = state.fields
            return true
        }

        override fun delete(identity: SyncIdentity): Boolean = records.remove(identity) != null

        fun set(type: SyncRecordType, key: String, fields: Map<String, String>) {
            records[SyncIdentity(type, key)] = fields
        }
    }

    /**
     * A peer that answers every request successfully, and counts them.
     *
     * The count is the point: "an invalid connection makes no request" is only assertable if the
     * transport is the thing being watched, and a request that never happens is invisible in any
     * other kind of test.
     */
    private class CountingTransport : WebDavTransport {

        var requests = 0
            private set

        override fun propfind(url: String, depth: Int, body: String): TransportResponse {
            requests++
            return TransportResponse(207, EMPTY_ROOT)
        }

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): TransportResponse {
            requests++
            return when (method) {
                "MKCOL" -> TransportResponse(201, "")
                "PUT" -> TransportResponse(201, "", "e1")
                "DELETE" -> TransportResponse(204, "")
                else -> TransportResponse(405, "")
            }
        }

        override fun download(url: String, headers: Map<String, String>): DownloadResponse {
            requests++
            return DownloadResponse(404, ByteArray(0), null)
        }

        companion object {
            /** A `207` carrying only the root collection: a complete, empty subtree. */
            private const val EMPTY_ROOT =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">" +
                    "<D:response><D:href>/dav/</D:href><D:propstat><D:prop>" +
                    "<D:resourcetype><D:collection/></D:resourcetype></D:prop>" +
                    "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"
        }
    }

    private fun runner(deviceId: String? = "device-a") = SyncRunner(
        state = state,
        store = store,
        transportFactory = WebDavTransportFactory { _, _, _ -> transport },
        now = { clock },
        deviceId = deviceId
    )

    private fun connection(url: String = ADDRESS) = WebDavConnection(
        serverUrl = url,
        username = "u",
        password = "p".toCharArray()
    )

    // ------------------------------------------------------------------------ cases

    @Test
    fun `an unusable connection makes no request at all`() {
        // The whole point of checking first: a blank address must not produce a dialled URL,
        // a timeout, or a 401 the user would then investigate on the server.
        val outcome = runner().runOnce(connection(url = ""))
        assertThat(outcome).isInstanceOf(SyncOutcome.NotUsable::class.java)
        assertThat((outcome as SyncOutcome.NotUsable).problem)
            .isEqualTo(WebDavConnection.Problem.BLANK)
        assertThat(transport.requests).isZero()
    }

    @Test
    fun `a first round against an empty peer uploads the book and says it synced`() {
        store.set(SyncRecordType.ACCOUNT, "acc1", mapOf("name" to "Cash", "type" to "CASH"))
        store.set(SyncRecordType.TRANSACTION, "tx1", mapOf("description" to "Coffee"))

        val outcome = runner().runOnce(connection())

        assertThat(outcome).isInstanceOf(SyncOutcome.Ran::class.java)
        val ran = outcome as SyncOutcome.Ran
        assertThat(ran.notice).isEqualTo(SyncNotice.SYNCED)
        assertThat(ran.result.uploaded).isEqualTo(2)
        // Both records are now the agreed base, which is what makes the next round a no-op
        // instead of a second upload.
        assertThat(state.getBases()).hasSize(2)
    }

    @Test
    fun `a round that decides nothing tells the user the two sides agree`() {
        val outcome = runner().runOnce(connection()) as SyncOutcome.Ran
        assertThat(outcome.notice).isEqualTo(SyncNotice.NOTHING_TO_DO)
        assertThat(outcome.result.movedAnything).isFalse()
    }

    @Test
    fun `this installation's device id is stamped on the tombstones the round writes`() {
        // A record the base knows about and the local book no longer has: the user deleted it
        // here, so the round propagates that as a tombstone — attributed to this device.
        state.putBase(SyncRecordType.TRANSACTION, "tx1", "{}", clock, "e0")

        val outcome = runner(deviceId = "device-a").runOnce(connection())

        assertThat(outcome).isInstanceOf(SyncOutcome.Ran::class.java)
        val tombstone = state.getTombstone(SyncRecordType.TRANSACTION, "tx1")
        assertThat(tombstone).isNotNull()
        assertThat(tombstone!!.deviceId).isEqualTo("device-a")
    }

    @Test
    fun `a store that throws is reported instead of escaping the round`() {
        // The store is SQLite; a throw here would otherwise be swallowed by the fragment's
        // background-thread guard and become "the button did nothing".
        store.throwOnList = true

        val outcome = runner().runOnce(connection())

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        // The listing was never reached: the local side is read first.
        assertThat(transport.requests).isZero()
    }

    @Test
    fun `an empty peer while the base knows records says the folder is wrong`() {
        // The record is present on both this device and the base, so nothing was deleted here —
        // and the peer answered a complete, empty listing. That is the "wrong folder" shape, and
        // it has to reach the caller as such: the fix is on the settings page, not the server.
        state.putBase(SyncRecordType.TRANSACTION, "tx1", "{}", clock, "e0")
        store.set(SyncRecordType.TRANSACTION, "tx1", emptyMap())

        val outcome = runner().runOnce(connection()) as SyncOutcome.Ran

        assertThat(outcome.result.abandoned).isTrue()
        assertThat(outcome.result.abandonReason).isEqualTo(SyncAbandon.EMPTY_REMOTE)
        assertThat(outcome.notice).isEqualTo(SyncNotice.WRONG_FOLDER)
        assertThat(outcome.result.movedAnything).isFalse()
    }

    companion object {
        private const val ADDRESS = "https://dav.example/dav"
    }
}
