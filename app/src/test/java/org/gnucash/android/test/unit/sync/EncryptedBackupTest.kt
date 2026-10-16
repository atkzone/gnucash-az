package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gnucash.android.sync.EncryptedBackup
import org.gnucash.android.sync.MalformedBackupException
import org.gnucash.android.sync.UnsupportedBackupVersionException
import org.gnucash.android.sync.WrongPasswordException
import org.junit.Test
import java.util.Base64
import java.util.Random

/**
 * The encrypted backup container (plan §8, feature ② v1).
 *
 * A plain JVM test: the format has no Android dependency, and every property worth
 * asserting here is "what happens when the bytes are not what we wrote" — which is exactly
 * what a fake can stage for free and a real device cannot stage at all.
 *
 * KDF iterations are pinned at [TEST_ITERATIONS] (1000, not the production 600k). At 600k
 * a single derivation costs hundreds of milliseconds and this class would take minutes
 * without covering anything extra — the iteration count is not what these cases are about.
 */
class EncryptedBackupTest {

    private companion object {
        const val TEST_ITERATIONS = 1_000
        val PASS = "correct horse battery staple".toCharArray()
        val BOOK = "this stands in for a gzipped GnuCash XML backup".toByteArray()
    }

    private fun encrypt(
        plain: ByteArray = BOOK,
        password: CharArray = PASS,
        iterations: Int = TEST_ITERATIONS
    ) = EncryptedBackup.encrypt(plain, password, iterations)

    private fun ByteArray.indexOfAscii(needle: String): Int {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..size - pattern.size) {
            for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    /** Replaces [old] with [new] (same length) everywhere it occurs. */
    private fun ByteArray.replaceAscii(old: String, new: String): ByteArray {
        require(old.length == new.length) { "same-length replacement only" }
        val pattern = old.toByteArray(Charsets.US_ASCII)
        val replacement = new.toByteArray(Charsets.US_ASCII)
        val out = copyOf()
        var index = out.indexOfAscii(old)
        check(index >= 0) { "pattern not found: $old" }
        while (index >= 0) {
            replacement.copyInto(out, index)
            index = out.indexOfAscii(old)
        }
        return out
    }

    /** Inserts [inserted] immediately before [anchor]. */
    private fun ByteArray.insertBeforeAscii(anchor: String, inserted: String): ByteArray {
        val index = indexOfAscii(anchor)
        check(index >= 0) { "anchor not found: $anchor" }
        return copyOfRange(0, index) +
            inserted.toByteArray(Charsets.US_ASCII) +
            copyOfRange(index, size)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * Builds a synthetic container **without** going through encryption, so each parse
     * rule can be exercised on its own (and without paying for a derivation).
     */
    private fun container(
        version: String = "1",
        kdf: String = EncryptedBackup.KDF_NAME,
        iterations: String = TEST_ITERATIONS.toString(),
        cipher: String = EncryptedBackup.CIPHER_NAME,
        keybits: String = EncryptedBackup.KEY_BITS.toString(),
        salt: String = b64(ByteArray(EncryptedBackup.SALT_BYTES)),
        iv: String = b64(ByteArray(EncryptedBackup.IV_BYTES)),
        body: ByteArray = ByteArray(EncryptedBackup.TAG_BITS / 8),
        extraLines: String = "",
        terminated: Boolean = true
    ): ByteArray = buildString {
        append(EncryptedBackup.MAGIC).append('\n')
        append("version: ").append(version).append('\n')
        append("kdf: ").append(kdf).append('\n')
        append("iterations: ").append(iterations).append('\n')
        append("cipher: ").append(cipher).append('\n')
        append("keybits: ").append(keybits).append('\n')
        append("salt: ").append(salt).append('\n')
        append("iv: ").append(iv).append('\n')
        append(extraLines)
        if (terminated) append(EncryptedBackup.END_MARKER).append('\n')
    }.toByteArray(Charsets.US_ASCII) + body

    // ----------------------------------------------------------------- round trip

    @Test
    fun `an encrypted backup round trips byte for byte`() {
        val opened = EncryptedBackup.decrypt(encrypt(), PASS)
        assertThat(opened).isEqualTo(BOOK)
    }

    @Test
    fun `an empty payload round trips`() {
        val opened = EncryptedBackup.decrypt(encrypt(plain = ByteArray(0)), PASS)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `a payload holding every byte value round trips`() {
        // 0x00 and 0xFF are where a "decode as text" shortcut would quietly corrupt.
        val all = ByteArray(512) { (it % 256).toByte() }
        val opened = EncryptedBackup.decrypt(encrypt(plain = all), PASS)
        assertThat(opened).isEqualTo(all)
    }

    @Test
    fun `a large payload round trips`() {
        val big = ByteArray(256 * 1024) { (it * 31 % 251).toByte() }
        val opened = EncryptedBackup.decrypt(encrypt(plain = big), PASS)
        assertThat(opened).isEqualTo(big)
    }

    // ----------------------------------------------------------- header properties

    @Test
    fun `a container starts with the magic line`() {
        val container = encrypt()
        val firstLine = String(container, Charsets.US_ASCII).substringBefore('\n')
        assertThat(firstLine).isEqualTo(EncryptedBackup.MAGIC)
        assertThat(EncryptedBackup.isEncrypted(container)).isTrue()
    }

    @Test
    fun `the header declares every parameter needed to open the container`() {
        val header = String(encrypt(iterations = 4_242), Charsets.US_ASCII)
            .substringBefore(EncryptedBackup.END_MARKER)

        assertThat(header).contains("version: ${EncryptedBackup.FORMAT_VERSION}")
        assertThat(header).contains("kdf: ${EncryptedBackup.KDF_NAME}")
        assertThat(header).contains("iterations: 4242")
        assertThat(header).contains("cipher: ${EncryptedBackup.CIPHER_NAME}")
        assertThat(header).contains("keybits: ${EncryptedBackup.KEY_BITS}")

        val salt = header.lineValue("salt")
        val iv = header.lineValue("iv")
        assertThat(Base64.getDecoder().decode(salt)).hasSize(EncryptedBackup.SALT_BYTES)
        assertThat(Base64.getDecoder().decode(iv)).hasSize(EncryptedBackup.IV_BYTES)
    }

    @Test
    fun `two encryptions of the same book are different`() {
        // A fresh salt and IV per call; reusing either across backups is the classic
        // "same key, same nonce" break.
        assertThat(encrypt()).isNotEqualTo(encrypt())
    }

    @Test
    fun `a pinned random source reproduces the container exactly`() {
        // The container must carry *everything*: nothing may depend on hidden state.
        val a = EncryptedBackup.encrypt(BOOK, PASS, TEST_ITERATIONS, Random(20261012))
        val b = EncryptedBackup.encrypt(BOOK, PASS, TEST_ITERATIONS, Random(20261012))
        assertThat(a).isEqualTo(b)
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `a wrong passphrase is refused as a wrong passphrase`() {
        val container = encrypt()
        assertThatThrownBy { EncryptedBackup.decrypt(container, "not the passphrase".toCharArray()) }
            .isInstanceOf(WrongPasswordException::class.java)
    }

    @Test
    fun `an empty passphrase cannot be used to encrypt`() {
        assertThatThrownBy { EncryptedBackup.encrypt(BOOK, CharArray(0), TEST_ITERATIONS) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a body byte flipped after the fact is refused`() {
        val container = encrypt()
        // Last byte is inside the GCM tag; flipping anything in the body must fail closed.
        container[container.size - 1] = (container[container.size - 1] + 1).toByte()
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(WrongPasswordException::class.java)
    }

    @Test
    fun `a header that gains a line is refused`() {
        // This edit changes no parsed field — so it can only be caught because the header
        // bytes are authenticated as associated data. Without the AAD it decrypts fine.
        val container = encrypt().insertBeforeAscii(EncryptedBackup.END_MARKER, "x-note: added\n")
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(WrongPasswordException::class.java)
    }

    @Test
    fun `a header whose iterations were rewritten is refused`() {
        val container = encrypt(iterations = 1_000).replaceAscii("iterations: 1000", "iterations: 2000")
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(WrongPasswordException::class.java)
    }

    @Test
    fun `a truncated container is refused as malformed`() {
        val container = encrypt()
        val half = container.copyOfRange(0, container.size / 2)
        assertThatThrownBy { EncryptedBackup.decrypt(half, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
    }

    @Test
    fun `bytes that are not a container are refused as malformed`() {
        val gzip = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00)
        assertThatThrownBy { EncryptedBackup.decrypt(gzip, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
    }

    @Test
    fun `a container that never ends its header is refused`() {
        val unterminated = container(terminated = false)
        assertThatThrownBy { EncryptedBackup.decrypt(unterminated, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
    }

    @Test
    fun `a container with a body shorter than the tag is refused`() {
        val stub = container(body = ByteArray(4))
        assertThatThrownBy { EncryptedBackup.decrypt(stub, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
    }

    @Test
    fun `a container from a newer format version is refused as such`() {
        val container = encrypt().replaceAscii("version: 1", "version: 2")
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(UnsupportedBackupVersionException::class.java)
            .hasMessageContaining("2")
    }

    @Test
    fun `a container naming an unknown algorithm is refused as malformed`() {
        // Algorithm names are pinned per version, so a different one under *our* version is
        // corruption, not a newer file (which would have arrived as a new version number).
        val container = container(kdf = "PBKDF2WithHmacSHA512")
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
            .hasMessageContaining("kdf")
    }

    @Test
    fun `a container with a wrong length salt is refused`() {
        val container = container(salt = b64(ByteArray(12)))
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
            .hasMessageContaining("salt")
    }

    @Test
    fun `a container with a non-numeric iteration count is refused`() {
        val container = container(iterations = "many")
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
            .hasMessageContaining("iterations")
    }

    @Test
    fun `a container repeating a field is refused`() {
        val container = container(extraLines = "version: 1\n")
        assertThatThrownBy { EncryptedBackup.decrypt(container, PASS) }
            .isInstanceOf(MalformedBackupException::class.java)
            .hasMessageContaining("duplicate")
    }

    @Test
    fun `a synthetic container with a valid header but made-up key material is refused`() {
        // Structurally perfect, cryptographically wrong: must still fail closed.
        assertThatThrownBy { EncryptedBackup.decrypt(container(), PASS) }
            .isInstanceOf(WrongPasswordException::class.java)
    }

    // ------------------------------------------------------------------- detection

    @Test
    fun `isEncrypted is true only for our containers`() {
        assertThat(EncryptedBackup.isEncrypted(encrypt())).isTrue()
        assertThat(EncryptedBackup.isEncrypted(ByteArray(0))).isFalse()
        assertThat(EncryptedBackup.isEncrypted("-----BEGIN GNUCASH".toByteArray())).isFalse()
        assertThat(EncryptedBackup.isEncrypted(BOOK)).isFalse()
        assertThat(EncryptedBackup.isEncrypted(byteArrayOf(0x1f, 0x8b.toByte()))).isFalse()
        // One byte off at the very end of the magic must not pass.
        val tweaked = encrypt()
        tweaked[EncryptedBackup.MAGIC.length - 1] = '!'.code.toByte()
        assertThat(EncryptedBackup.isEncrypted(tweaked)).isFalse()
    }

    private fun String.lineValue(name: String): String =
        lineSequence().first { it.startsWith("$name: ") }.substringAfter("$name: ").trim()
}
