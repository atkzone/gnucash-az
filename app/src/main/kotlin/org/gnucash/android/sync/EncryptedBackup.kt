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

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The optional-encryption container for a whole-book backup (plan §8, feature ② v1).
 *
 * The payload is a book backup as the app already writes it (gzipped GnuCash XML), i.e.
 * bytes it never interprets. This object wraps those bytes in a **self-describing**
 * container so that decryption needs nothing but the container and the passphrase:
 *
 * ```
 * -----BEGIN GNUCASH POCKET BACKUP-----
 * version: 1
 * kdf: PBKDF2WithHmacSHA256
 * iterations: 600000
 * cipher: AES/GCM/NoPadding
 * keybits: 256
 * salt: <base64>
 * iv: <base64>
 * -----END GNUCASH POCKET BACKUP HEADER-----
 * <raw ciphertext, tag included>
 * ```
 *
 * ### Why the header is text and in front
 *
 * It is what makes [isEncrypted] a one-glance check, so the restore path can never feed
 * an encrypted blob to the importer as if it were a plain `.gnca` (or the reverse). It
 * also means a future version can read the parameters it needs to decrypt *before*
 * decrypting, which a binary trailer could not.
 *
 * ### Why the header is authenticated
 *
 * The header bytes are passed to [Cipher.updateAAD], so they are covered by the GCM tag.
 * Without that, an attacker (or a sync that mishandles the file) could rewrite
 * `iterations` or `salt` and the change would be **undetectable up to the point of
 * producing garbage** — the classic "it opened, but the book is nonsense". With the AAD
 * in place, any edit to the header fails the same single check as a wrong passphrase, so
 * there is exactly one failure path to reason about.
 *
 * ### What the version number is for
 *
 * [FORMAT_VERSION] is the **only** forward-compatibility gate: the algorithm names are
 * pinned per version, so bumping `version` alone is how a future format is spelled. Any
 * *other* value in the header that we cannot honour is therefore a corrupt container, not
 * a newer one, and is reported as [MalformedBackupException].
 *
 * ### The one thing this cannot tell apart
 *
 * AES-GCM authenticates but does not distinguish "wrong key" from "tampered bytes": both
 * surface as [AEADBadTagException]. [decrypt] maps that to [WrongPasswordException],
 * whose message names both possibilities on purpose — telling the user "wrong passphrase"
 * when the file was actually corrupted would send them hunting for the wrong problem.
 *
 * Deliberately free of Android dependencies so the whole format can be exercised on a
 * plain JVM (see `EncryptedBackupTest`).
 */
object EncryptedBackup {

    /** First line of an encrypted container. Also the cheap test in [isEncrypted]. */
    const val MAGIC: String = "-----BEGIN GNUCASH POCKET BACKUP-----"

    /** Terminates the header. Everything after its line is raw ciphertext. */
    const val END_MARKER: String = "-----END GNUCASH POCKET BACKUP HEADER-----"

    const val FORMAT_VERSION: Int = 1

    const val KDF_NAME: String = "PBKDF2WithHmacSHA256"
    const val CIPHER_NAME: String = "AES/GCM/NoPadding"

    /** Derived-key length. Fixed per [FORMAT_VERSION]; a change here needs a version bump. */
    const val KEY_BITS: Int = 256

    /** GCM tag length. Fixed per [FORMAT_VERSION]. */
    const val TAG_BITS: Int = 128

    /** Salt length in bytes. Fixed per [FORMAT_VERSION]. */
    const val SALT_BYTES: Int = 16

    /**
     * IV (GCM nonce) length in bytes. 12 is the size GCM is defined for; anything else
     * makes the cipher work harder and buys nothing.
     */
    const val IV_BYTES: Int = 12

    /**
     * KDF iterations used in production.
     *
     * Injectable ([encrypt]'s `iterations`) so tests can use a small value: at 600k a
     * single derivation costs hundreds of milliseconds and a test class that encrypts a
     * dozen payloads would take minutes for no added coverage.
     */
    const val DEFAULT_ITERATIONS: Int = 600_000

    /** Upper bound on iterations read from a container, so a corrupt header cannot stall us. */
    private const val MAX_ITERATIONS: Int = 10_000_000

    // ------------------------------------------------------------------ detection

    /**
     * `true` when [bytes] starts with [MAGIC], i.e. when it is one of our containers.
     *
     * Never throws and never looks past the magic: it is the guard the restore path calls
     * *before* touching anything, so it must be cheap and safe on truncated or hostile
     * input. A plain `.gnca` (gzip) cannot collide — gzip starts with `1f 8b`.
     */
    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.length) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i].code.toByte()) return false
        }
        return true
    }

    // ------------------------------------------------------------------- encrypt

    /**
     * Wraps [plain] in an encrypted container under [password].
     *
     * @param iterations PBKDF2 iteration count; defaults to [DEFAULT_ITERATIONS].
     * @param random     the source of the salt and IV. Injectable only so tests can pin
     *                   them; production must leave it as the default [SecureRandom].
     * @throws IllegalArgumentException if [password] is empty or [iterations] is out of range.
     */
    fun encrypt(
        plain: ByteArray,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: Random = SecureRandom()
    ): ByteArray {
        require(password.isNotEmpty()) { "a backup passphrase must not be empty" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range: $iterations" }

        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val header = buildHeader(iterations, salt, iv).toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(password, salt, iterations), "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    // ------------------------------------------------------------------- decrypt

    /**
     * Opens [container] with [password].
     *
     * @throws MalformedBackupException          when [container] is not a well-formed container
     * @throws UnsupportedBackupVersionException when it is well-formed but its version is not ours
     * @throws WrongPasswordException            when the passphrase does not open it (which is
     *                                           also what a tampered container looks like)
     */
    fun decrypt(container: ByteArray, password: CharArray): ByteArray {
        val parsed = parse(container)
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveKey(password, parsed.salt, parsed.iterations), "AES"),
            GCMParameterSpec(TAG_BITS, parsed.iv)
        )
        cipher.updateAAD(parsed.header)
        return try {
            cipher.doFinal(parsed.body)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException(
                "the passphrase does not open this backup, or the backup was altered",
                e
            )
        } catch (e: GeneralSecurityException) {
            // Anything else from the JCE (e.g. an unusable key) is a broken container
            // rather than a wrong guess, but it must still be a *Typed* refusal.
            throw MalformedBackupException("the backup could not be decrypted: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(KDF_NAME).generateSecret(spec).encoded
        } finally {
            // Do not leave the passphrase copy in the spec; the caller's array is theirs to clear.
            spec.clearPassword()
        }
    }

    private fun buildHeader(iterations: Int, salt: ByteArray, iv: ByteArray): String = buildString {
        append(MAGIC).append('\n')
        append("version: ").append(FORMAT_VERSION).append('\n')
        append("kdf: ").append(KDF_NAME).append('\n')
        append("iterations: ").append(iterations).append('\n')
        append("cipher: ").append(CIPHER_NAME).append('\n')
        append("keybits: ").append(KEY_BITS).append('\n')
        append("salt: ").append(encode(salt)).append('\n')
        append("iv: ").append(encode(iv)).append('\n')
        append(END_MARKER).append('\n')
    }

    private class Parsed(
        val header: ByteArray,
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val body: ByteArray
    )

    private fun parse(container: ByteArray): Parsed {
        if (!isEncrypted(container)) {
            throw MalformedBackupException(
                "not a GnuCash Pocket encrypted backup: the magic line is missing"
            )
        }
        val reader = LineReader(container)
        reader.readLine() // the magic, already verified by isEncrypted

        val fields = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine()
                ?: throw MalformedBackupException("the backup header is not terminated")
            if (line == END_MARKER) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw MalformedBackupException("malformed header line: $line")
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isEmpty() || value.isEmpty()) {
                throw MalformedBackupException("malformed header line: $line")
            }
            if (fields.put(key, value) != null) {
                throw MalformedBackupException("duplicate header field: $key")
            }
        }

        // The version gate comes first: an unknown version means the field set below is
        // not necessarily the one this version defines, so judging the rest would be
        // reading another format's bytes as ours.
        val version = intField(fields, "version")
        if (version != FORMAT_VERSION) throw UnsupportedBackupVersionException(version)

        requireField(fields, "kdf", KDF_NAME)
        requireField(fields, "cipher", CIPHER_NAME)
        requireField(fields, "keybits", KEY_BITS.toString())

        val iterations = intField(fields, "iterations")
        if (iterations !in 1..MAX_ITERATIONS) {
            throw MalformedBackupException("iterations out of range: $iterations")
        }
        val salt = bytesField(fields, "salt", SALT_BYTES)
        val iv = bytesField(fields, "iv", IV_BYTES)

        val bodyStart = reader.position
        val bodyLength = container.size - bodyStart
        if (bodyLength < TAG_BITS / 8) {
            throw MalformedBackupException(
                "the backup body is too short to hold an authentication tag ($bodyLength bytes)"
            )
        }
        return Parsed(
            header = container.copyOfRange(0, bodyStart),
            iterations = iterations,
            salt = salt,
            iv = iv,
            body = container.copyOfRange(bodyStart, container.size)
        )
    }

    private fun requireField(fields: Map<String, String>, name: String, expected: String) {
        val actual = fields[name]
            ?: throw MalformedBackupException("the backup header has no `$name`")
        if (actual != expected) {
            // Algorithm names are pinned per FORMAT_VERSION; a different one under our
            // version is corruption, and a genuinely new algorithm arrives as a new version.
            throw MalformedBackupException("unsupported $name: $actual")
        }
    }

    private fun intField(fields: Map<String, String>, name: String): Int {
        val raw = fields[name] ?: throw MalformedBackupException("the backup header has no `$name`")
        return raw.toIntOrNull() ?: throw MalformedBackupException("`$name` is not a number: $raw")
    }

    private fun bytesField(fields: Map<String, String>, name: String, length: Int): ByteArray {
        val raw = fields[name] ?: throw MalformedBackupException("the backup header has no `$name`")
        val bytes = try {
            decode(raw)
        } catch (e: IllegalArgumentException) {
            throw MalformedBackupException("`$name` is not base64: ${e.message}", e)
        }
        if (bytes.size != length) {
            throw MalformedBackupException("`$name` must be $length bytes, got ${bytes.size}")
        }
        return bytes
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    /**
     * Reads the container's ASCII header line by line, leaving [position] right after the
     * newline of the line it last returned — i.e. exactly where the body begins once
     * [END_MARKER] has been read.
     */
    private class LineReader(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        fun readLine(): String? {
            if (position >= bytes.size) return null
            val start = position
            var index = start
            while (index < bytes.size && bytes[index] != NL) index++
            var end = index
            if (end > start && bytes[end - 1] == CR) end--
            position = if (index < bytes.size) index + 1 else index
            return String(bytes, start, end - start, Charsets.US_ASCII)
        }

        private companion object {
            const val NL: Byte = '\n'.code.toByte()
            const val CR: Byte = '\r'.code.toByte()
        }
    }
}

/** Base class of every refusal from [EncryptedBackup]. */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The bytes are not a container of ours (bad magic, truncated header, bad field, short body). */
class MalformedBackupException(message: String, cause: Throwable? = null) :
    BackupException(message, cause)

/**
 * The container is well-formed but its [EncryptedBackup.FORMAT_VERSION] is not one this
 * build understands. Kept separate from [MalformedBackupException] so the user is told
 * "this backup was made by a newer version" rather than "this file is broken".
 */
class UnsupportedBackupVersionException(val version: Int) :
    BackupException("unsupported backup format version: $version")

/**
 * The passphrase did not open the container — or the container was altered, which under
 * AES-GCM is indistinguishable. See the class doc on [EncryptedBackup].
 */
class WrongPasswordException(message: String, cause: Throwable? = null) :
    BackupException(message, cause)
