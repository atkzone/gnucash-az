package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.ResolveSide
import org.junit.Test

/**
 * The persisted vocabulary of a conflict resolution (feature ② v3).
 *
 * Plain JVM: [ResolveSide] is pure, and the one thing worth pinning is the **id**, because it
 * is what lands in `sync_resolutions.side`. Renaming a Kotlin constant must not orphan rows
 * written by an older build — the same rule [org.gnucash.android.sync.SyncRecordType] follows.
 */
class ConflictResolutionTest {

    @Test
    fun `the persisted ids are the contract, not the constant names`() {
        assertThat(ResolveSide.LOCAL.id).isEqualTo("local")
        assertThat(ResolveSide.REMOTE.id).isEqualTo("remote")
    }

    @Test
    fun `every side round trips through its persisted id`() {
        for (side in ResolveSide.entries) {
            assertThat(ResolveSide.fromId(side.id)).isEqualTo(side)
        }
        assertThat(ResolveSide.entries.map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    fun `an id we did not write resolves to nothing rather than to a guess`() {
        // A row from a newer build, or a corrupt one. Answering with a default would silently
        // force a side the user never chose — the exact class of bug this feature is about.
        assertThat(ResolveSide.fromId("sidelong")).isNull()
        assertThat(ResolveSide.fromId(null)).isNull()
        assertThat(ResolveSide.fromId("")).isNull()
        assertThat(ResolveSide.fromId("LOCAL")).isNull()
    }
}
