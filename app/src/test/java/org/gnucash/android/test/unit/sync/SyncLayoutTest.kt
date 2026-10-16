package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gnucash.android.sync.SyncIdentity
import org.gnucash.android.sync.SyncLayout
import org.gnucash.android.sync.SyncRecordType
import org.junit.Test

/**
 * The remote layout (plan §5② step 4, §7.1).
 *
 * The join between "what the peer listed" and "what we know locally" is a string, so
 * it is exactly the kind of thing that breaks silently: a path that half-parses into a
 * different identity reads as one phantom record plus one deletion. These cases pin the
 * round trip and, more importantly, the *refusals*.
 */
class SyncLayoutTest {

    private val tx = SyncRecordType.TRANSACTION

    // ------------------------------------------------------------------ pathFor

    @Test
    fun `a record path is a type-named folder with the key as the file name`() {
        assertThat(SyncLayout.pathFor(tx, "8f2a4c")).isEqualTo("/transaction/8f2a4c.gncs")
    }

    @Test
    fun `every record type has its own folder`() {
        for (type in SyncRecordType.entries) {
            assertThat(SyncLayout.pathFor(type, "k"))
                .isEqualTo("/${type.id}/k.gncs")
        }
    }

    @Test
    fun `a key that would smuggle a path separator is refused, not encoded away`() {
        // A key with a slash would produce a path that `parse` then refuses; silently
        // writing it would show up as "the record we just wrote is not there".
        assertThatThrownBy { SyncLayout.pathFor(tx, "a/b") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a blank key or one that starts with a dot is refused`() {
        assertThatThrownBy { SyncLayout.pathFor(tx, "") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SyncLayout.pathFor(tx, ".hidden") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // -------------------------------------------------------------------- parse

    @Test
    fun `a record path round trips for every record type`() {
        for (type in SyncRecordType.entries) {
            val identity = SyncIdentity(type, "8f2a4c-0001")
            assertThat(SyncLayout.parse(SyncLayout.pathFor(identity))).isEqualTo(identity)
        }
    }

    @Test
    fun `a path that is not ours is refused rather than guessed at`() {
        assertThat(SyncLayout.parse("/transaction/abc.txt")).isNull()      // wrong extension
        assertThat(SyncLayout.parse("/transaction/abc")).isNull()          // no extension
        assertThat(SyncLayout.parse("/unknown/abc.gncs")).isNull()         // unknown type
        assertThat(SyncLayout.parse("/transaction/nested/abc.gncs")).isNull() // too deep
        assertThat(SyncLayout.parse("/abc.gncs")).isNull()                 // no type folder
        assertThat(SyncLayout.parse("/transaction/.gncs")).isNull()        // empty key
        assertThat(SyncLayout.parse("")).isNull()
    }

    @Test
    fun `a scheme prefix, a query string, a trailing slash and a backslash are tolerated`() {
        val expected = SyncIdentity(tx, "abc")
        // A base *path* prefix (a Nextcloud `/remote.php/dav/files/u/...`) is the client's
        // job to strip before anything reaches here; the layout only unwraps scheme+host.
        assertThat(SyncLayout.parse("https://host/transaction/abc.gncs")).isEqualTo(expected)
        assertThat(SyncLayout.parse("/transaction/abc.gncs?x=1")).isEqualTo(expected)
        assertThat(SyncLayout.parse("/transaction/abc.gncs/")).isEqualTo(expected)
        assertThat(SyncLayout.parse("\\transaction\\abc.gncs")).isEqualTo(expected)
    }

    // ------------------------------------------------------------------ identity

    @Test
    fun `an identity token round trips, including a key that itself contains a colon`() {
        val identity = SyncIdentity(tx, "a:b")
        assertThat(SyncIdentity.fromToken(identity.token)).isEqualTo(identity)
    }

    @Test
    fun `a malformed token is refused`() {
        assertThat(SyncIdentity.fromToken("nope")).isNull()
        assertThat(SyncIdentity.fromToken(":x")).isNull()
        assertThat(SyncIdentity.fromToken("transaction:")).isNull()
        assertThat(SyncIdentity.fromToken("nosuchtype:x")).isNull()
    }
}
