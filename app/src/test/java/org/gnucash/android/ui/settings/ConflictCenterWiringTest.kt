package org.gnucash.android.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * The one thing a unit test of the conflict centre cannot reach: that the sync result actually
 * **opens** it.
 *
 * Everything else about the screen is asserted directly ([ConflictDigestTest],
 * [ConflictCenterCodecTest], [ConflictCenterDialogTest]) — but the wiring lives inside a private
 * method of a preferences fragment, reached only after a network round, and its whole body is
 * `if (…) show(…)`. That is exactly the shape that goes missing without a failure anywhere: the
 * strings exist, the dialog works, and no user ever sees it because the call was dropped during
 * a refactor. There is no new literal on the path for a dex check to notice either.
 *
 * So the wiring is *pinned*: the call site is present, and it is **before** the early return that
 * skips the count line. The second half matters because the count line is gated on
 * `deservesCounts`, and a conflict list must not be gated with it — a round with something to
 * review is not a round with nothing to say.
 *
 * Deliberately a plain JVM test: it reads one source file, so it needs no device and runs in the
 * fast pass.
 */
class ConflictCenterWiringTest {

    private fun sourceText(relative: String): String {
        // The unit-test working directory is the module in a Gradle run, but a Robolectric or IDE
        // runner may start elsewhere; look in the few places it can legitimately be.
        val candidates = listOf(File(relative), File("app/$relative"))
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            for (candidate in candidates) {
                val file = File(dir, candidate.path)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `a round with parked records opens the conflict centre`() {
        val source = sourceText(FRAGMENT)
        assertThat(source)
            .`as`("the sync result must offer the conflict centre")
            .contains("showConflicts(result.reviews)")
    }

    @Test
    fun `a round with parked records opens it even when there is nothing to count`() {
        val source = sourceText(FRAGMENT)
        val opens = source.indexOf("showConflicts(result.reviews)")
        val gate = source.indexOf("if (!outcome.notice.deservesCounts) return")
        assertThat(opens)
            .`as`("the conflict centre call is present")
            .isGreaterThanOrEqualTo(0)
        assertThat(gate)
            .`as`("the counts gate is present")
            .isGreaterThanOrEqualTo(0)
        assertThat(opens)
            .`as`("conflicts must be shown before the counts gate can skip the rest")
            .isLessThan(gate)
    }

    @Test
    fun `the conflict centre is reachable from the backup settings page`() {
        // The fragment that opens it must be the one on that page — the dialog is not wired to
        // any other screen, so a change of host would silently leave the rows unreachable.
        assertThat(sourceText(FRAGMENT)).contains("ConflictCenterDialogFragment.newInstance(reviews)")
    }

    // --------------------------------------------------- the resolve buttons (② v3)

    @Test
    fun `the detail offers a way to settle the row`() {
        // The row's *kind* decides which sides exist, so an unreadable record cannot be offered
        // an answer that could never be honoured. Pinning `sidesFor(row.kind)` — rather than the
        // two button labels — is what keeps that rule from being flattened into two constants.
        val source = sourceText(DIALOG)
        assertThat(source)
            .`as`("the detail must ask which sides this row can be settled to")
            .contains("ConflictCenterText.sidesFor(row.kind)")
    }

    @Test
    fun `pressing a side goes through the single write path`() {
        val source = sourceText(DIALOG)
        assertThat(source)
            .`as`("the answer must be stored through ConflictResolutionStore")
            .contains("ConflictResolutionStore.remember(row.identity, side)")
    }

    @Test
    fun `the dialog stores an answer but never writes the book itself`() {
        // Applying an answer is the next round's job. A second write path here would be a second
        // set of failure modes on the local book, and the user would see a record change without
        // having synced — the one thing the screen promises not to do.
        val source = sourceText(DIALOG)
        assertThat(source).doesNotContain("LocalRecordStore")
        assertThat(source).doesNotContain("putBase(")
        assertThat(source).doesNotContain("store.write(")
    }

    private companion object {
        const val FRAGMENT =
            "src/main/java/org/gnucash/android/ui/settings/BackupPreferenceFragment.kt"

        /** Host of the resolve buttons; a different file than [FRAGMENT] on purpose. */
        const val DIALOG =
            "src/main/java/org/gnucash/android/ui/settings/ConflictCenterDialogFragment.kt"
    }
}
