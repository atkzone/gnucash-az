package org.gnucash.android.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.db.DatabaseSchema.SyncResolutionEntry
import org.gnucash.android.db.adapter.AccountsDbAdapter
import org.gnucash.android.db.adapter.SyncStateDbAdapter
import org.gnucash.android.sync.ResolveSide
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncRecordType
import org.gnucash.android.test.unit.GnuCashTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The conflict centre's one write path (feature ② v3).
 *
 * Two things matter here and neither is the happy path: that pressing a button really reaches
 * the book database, and that a write which **fails** is reported rather than swallowed — a
 * click listener that throws would close the dialog and leave no trace, which is
 * exactly the "button that silently does nothing" this feature exists to avoid.
 */
class ConflictResolutionStoreTest : GnuCashTest() {

    private lateinit var state: SyncStateDbAdapter

    @Before
    fun setUp() {
        state = SyncStateDbAdapter.instance
        state.deleteAllRecords()
        AccountsDbAdapter.instance.holder.db.delete(SyncResolutionEntry.TABLE_NAME, null, null)
    }

    @After
    fun tearDown() {
        state.deleteAllRecords()
        AccountsDbAdapter.instance.holder.db.delete(SyncResolutionEntry.TABLE_NAME, null, null)
    }

    private val identity = SyncIdentity(SyncRecordType.TRANSACTION, "t1")

    @Test
    fun `an answer really lands in the book`() {
        assertThat(ConflictResolutionStore.remember(identity, ResolveSide.LOCAL)).isTrue()

        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1"))
            .isEqualTo(ResolveSide.LOCAL)
    }

    @Test
    fun `choosing again replaces the previous answer`() {
        ConflictResolutionStore.remember(identity, ResolveSide.LOCAL)
        ConflictResolutionStore.remember(identity, ResolveSide.REMOTE)

        assertThat(state.getResolutions())
            .containsEntry(identity, ResolveSide.REMOTE)
            .hasSize(1)
    }

    @Test
    fun `a write that reports failure is reported as a failure, not swallowed`() {
        // SQLite reports a failed insert by returning `-1`, not by throwing — so "no exception"
        // must not be mistaken for "it was stored".
        val stored = ConflictResolutionStore.store(identity, ResolveSide.LOCAL) { _, _, _ -> false }

        assertThat(stored).isFalse()
        assertThat(state.resolutionCount()).isZero()
    }

    @Test
    fun `a write that throws is reported as a failure, not swallowed`() {
        val stored = ConflictResolutionStore.store(identity, ResolveSide.REMOTE) { _, _, _ ->
            throw IllegalStateException("the book database is gone")
        }

        assertThat(stored).isFalse()
        assertThat(state.resolutionCount()).isZero()
    }

    @Test
    fun `a failed write leaves the previous answer alone`() {
        ConflictResolutionStore.remember(identity, ResolveSide.LOCAL)

        val stored = ConflictResolutionStore.store(identity, ResolveSide.REMOTE) { _, _, _ ->
            throw IllegalStateException("the book database is gone")
        }

        assertThat(stored).isFalse()
        assertThat(state.getResolution(SyncRecordType.TRANSACTION, "t1"))
            .isEqualTo(ResolveSide.LOCAL)
    }
}
