package org.gnucash.android.test.unit.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gnucash.android.sync.RecordCodec
import org.junit.Test

/**
 * The canonical record encoding (plan §5② step 4).
 *
 * The base snapshot is compared for equality against a freshly decoded record, so a lossy
 * encode shows up as "a record that did not change is reported as changed" — or, worse,
 * as a deletion. These cases are about **losslessness** first and strictness second.
 */
class RecordCodecTest {

    @Test
    fun `keys are sorted so the same map always encodes identically`() {
        val a = mapOf("z" to "1", "a" to "2", "m" to "3")
        val b = mapOf("m" to "3", "a" to "2", "z" to "1")
        assertThat(RecordCodec.encode(a)).isEqualTo("a=2\nm=3\nz=1")
        assertThat(RecordCodec.encode(b)).isEqualTo(RecordCodec.encode(a))
    }

    @Test
    fun `a value with newlines, equals signs and percent signs survives the round trip`() {
        val fields = mapOf(
            "notes" to "line one\nline two",
            "expr" to "a=b=c",
            "pct" to "50% off",
            "esc" to "%25",
            "crlf" to "a\r\nb"
        )
        assertThat(RecordCodec.decode(RecordCodec.encode(fields))).isEqualTo(fields)
    }

    @Test
    fun `CJK text survives the UTF-8 byte round trip`() {
        val fields = mapOf("description" to "午饭#餐饮 ￥12.30", "notes" to "备注：测试")
        val bytes = RecordCodec.encodeBytes(fields)
        assertThat(RecordCodec.decodeBytes(bytes)).isEqualTo(fields)
    }

    @Test
    fun `an empty map is the empty string and decodes back to empty`() {
        assertThat(RecordCodec.encode(emptyMap())).isEmpty()
        assertThat(RecordCodec.decode("")).isEmpty()
    }

    @Test
    fun `an empty value survives the round trip`() {
        val fields = mapOf("notes" to "")
        assertThat(RecordCodec.decode(RecordCodec.encode(fields))).isEqualTo(fields)
    }

    @Test
    fun `a line without a separator is refused rather than half-read`() {
        // A malformed payload must not silently lose fields: fewer fields is a *different*
        // record that would be adopted as if the peer had edited it.
        assertThatThrownBy { RecordCodec.decode("a=1\nnonsense") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decodeOrNull turns a malformed payload into null instead of throwing`() {
        assertThat(RecordCodec.decodeOrNull("a=1\nnonsense")).isNull()
        assertThat(RecordCodec.decodeOrNull("a=1")).isEqualTo(mapOf("a" to "1"))
    }

    @Test
    fun `an unknown percent escape is left verbatim, never guessed at`() {
        // Only the four escapes this codec emits are decoded. A raw `%20` is not a space —
        // guessing would corrupt a value that legitimately contains a percent sign.
        assertThat(RecordCodec.decode("k=%20")["k"]).isEqualTo("%20")
        assertThat(RecordCodec.decode("k=%2F")["k"]).isEqualTo("%2F")
    }

    @Test
    fun `a duplicate key keeps the last writer, deterministically`() {
        assertThat(RecordCodec.decode("a=1\na=2")).isEqualTo(mapOf("a" to "2"))
    }
}
