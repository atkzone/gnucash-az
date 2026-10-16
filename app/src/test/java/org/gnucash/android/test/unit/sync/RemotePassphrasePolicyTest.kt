package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.gnucash.android.sync.PassphraseDecision
import org.gnucash.android.sync.PassphraseEntry
import org.gnucash.android.sync.RemotePassphrasePolicy
import org.junit.Test

/**
 * The rules for the remote-backup passphrase dialog.
 *
 * Every branch here is one the user can act on, and three of them exist specifically because
 * the naive implementation gets them *quietly* wrong — see [whitespace is a passphrase] and
 * [an empty first field is not an empty form].
 */
class RemotePassphrasePolicyTest {

    private fun decide(
        passphrase: String,
        confirmation: String,
        acknowledged: Boolean = false
    ): PassphraseDecision =
        RemotePassphrasePolicy.decide(PassphraseEntry(passphrase, confirmation, acknowledged))

    private fun invalidProblem(decision: PassphraseDecision) =
        (decision as PassphraseDecision.Invalid).problem

    @Test
    fun `an untouched form means no encryption`() {
        assertThat(decide("", "")).isEqualTo(PassphraseDecision.None)
    }

    @Test
    fun `no encryption does not need the irreversible acknowledgement`() {
        // The tick guards *setting* a passphrase. Requiring it here would make "I do not want
        // encryption" impossible to submit without lying about understanding something.
        assertThat(decide("", "", acknowledged = false)).isEqualTo(PassphraseDecision.None)
        assertThat(decide("", "", acknowledged = true)).isEqualTo(PassphraseDecision.None)
    }

    @Test
    fun `whitespace is a passphrase, not an empty form`() {
        // "   " is a real (unwise) passphrase. Treating it as empty would upload the book in
        // the clear while the user believes it was encrypted with what they typed.
        val decision = decide("   ", "   ", acknowledged = true)
        assertThat(decision).isInstanceOf(PassphraseDecision.Use::class.java)
        assertThat((decision as PassphraseDecision.Use).passphrase).isEqualTo("   ".toCharArray())
    }

    @Test
    fun `a passphrase is never trimmed`() {
        val decision = decide(" secret ", " secret ", acknowledged = true)
        assertThat((decision as PassphraseDecision.Use).passphrase)
            .isEqualTo(" secret ".toCharArray())
    }

    @Test
    fun `an empty first field is not an empty form`() {
        // Only the confirmation was filled in. Silently ignoring it would upload in the clear.
        assertThat(invalidProblem(decide("", "secret", acknowledged = true)))
            .isEqualTo(PassphraseDecision.Problem.CONFIRMATION_WITHOUT_PASSPHRASE)
    }

    @Test
    fun `the two fields must agree`() {
        assertThat(invalidProblem(decide("secret", "secert", acknowledged = true)))
            .isEqualTo(PassphraseDecision.Problem.CONFIRMATION_MISMATCH)
    }

    @Test
    fun `an empty confirmation is a mismatch, not a silent pass`() {
        assertThat(invalidProblem(decide("secret", "", acknowledged = true)))
            .isEqualTo(PassphraseDecision.Problem.CONFIRMATION_MISMATCH)
    }

    @Test
    fun `setting a passphrase requires the irreversible acknowledgement`() {
        assertThat(invalidProblem(decide("secret", "secret", acknowledged = false)))
            .isEqualTo(PassphraseDecision.Problem.ACKNOWLEDGEMENT_MISSING)
    }

    @Test
    fun `agreed and acknowledged is a usable passphrase`() {
        val decision = decide("correct horse", "correct horse", acknowledged = true)
        assertThat(decision).isInstanceOf(PassphraseDecision.Use::class.java)
        assertThat((decision as PassphraseDecision.Use).passphrase)
            .isEqualTo("correct horse".toCharArray())
    }

    @Test
    fun `the decision never prints the passphrase`() {
        // Decisions end up in log lines and crash reports; the secret must not ride along.
        val text = decide("correct horse", "correct horse", acknowledged = true).toString()
        assertThat(text).doesNotContain("correct horse")
        assertThat(text).contains("redacted")
    }
}
