package org.gnucash.android.ui.template

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gnucash.android.test.unit.GnuCashTest
import org.json.JSONException
import org.json.JSONObject
import org.junit.Test
import java.math.BigDecimal

/**
 * Tests for the transaction-template payload codec (feature ①d).
 *
 * The two things worth pinning down are the ones that would fail *silently* in
 * production:
 *
 * - **"空值" must survive.** A template saved without an amount has to come back
 *   without one — if it decoded to `0`, using that template would post a zero
 *   transaction that looks deliberate.
 * - **A corrupt amount must be reported.** Decoding `"amount":"abc"` has to
 *   throw rather than degrade to `0`.
 */
class TransactionTemplateCodecTest : GnuCashTest() {

    @Test
    fun `fixed amount round trips`() {
        val template = TransactionTemplate(
            name = "房租",
            debitAccountUid = "debit-uid",
            creditAccountUid = "credit-uid",
            amount = BigDecimal("1234.56"),
            description = "十月房租",
            notes = "含物业",
            number = "2026-10",
            debitMemo = "借方备注",
            creditMemo = "贷方备注"
        )

        val decoded = TransactionTemplateCodec.decode(TransactionTemplateCodec.encode(template))

        assertThat(decoded.debitAccountUid).isEqualTo("debit-uid")
        assertThat(decoded.creditAccountUid).isEqualTo("credit-uid")
        assertThat(decoded.amount).isEqualByComparingTo(BigDecimal("1234.56"))
        assertThat(decoded.description).isEqualTo("十月房租")
        assertThat(decoded.notes).isEqualTo("含物业")
        assertThat(decoded.number).isEqualTo("2026-10")
        assertThat(decoded.debitMemo).isEqualTo("借方备注")
        assertThat(decoded.creditMemo).isEqualTo("贷方备注")
    }

    @Test
    fun `blank amount stays blank - the key is omitted, not written as zero`() {
        val template = TransactionTemplate(
            name = "喝水",
            debitAccountUid = "a",
            creditAccountUid = "b",
            amount = null
        )

        val json = TransactionTemplateCodec.encode(template)
        assertThat(JSONObject(json).has("amount"))
            .`as`("a 空值 amount must not be written at all")
            .isFalse()

        val decoded = TransactionTemplateCodec.decode(json)
        assertThat(decoded.amount).isNull()
        assertThat(decoded.hasFixedAmount).isFalse()
    }

    @Test
    fun `an explicit zero amount is not the same as 空值`() {
        val json = TransactionTemplateCodec.encode(
            TransactionTemplate(name = "零元", amount = BigDecimal.ZERO)
        )

        assertThat(JSONObject(json).has("amount")).isTrue()
        val decoded = TransactionTemplateCodec.decode(json)
        assertThat(decoded.amount).isNotNull()
        assertThat(decoded.amount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(decoded.hasFixedAmount).isTrue()
    }

    @Test
    fun `blank strings are omitted so they come back blank`() {
        val json = TransactionTemplateCodec.encode(TransactionTemplate(name = "空字段"))

        val obj = JSONObject(json)
        assertThat(obj.has("description")).isFalse()
        assertThat(obj.has("notes")).isFalse()
        assertThat(obj.has("number")).isFalse()
        assertThat(obj.has("debit_memo")).isFalse()
        assertThat(obj.has("credit_memo")).isFalse()
        assertThat(obj.has("debit_account_uid")).isFalse()

        val decoded = TransactionTemplateCodec.decode(json)
        assertThat(decoded.description).isEmpty()
        assertThat(decoded.notes).isEmpty()
        assertThat(decoded.debitAccountUid).isNull()
    }

    @Test
    fun `payload carries the format version`() {
        val json = TransactionTemplateCodec.encode(TransactionTemplate(name = "v"))
        assertThat(JSONObject(json).getInt("version")).isEqualTo(TransactionTemplateCodec.CURRENT_VERSION)
    }

    @Test
    fun `decoding a blank payload is rejected`() {
        assertThatThrownBy { TransactionTemplateCodec.decode(null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TransactionTemplateCodec.decode("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TransactionTemplateCodec.decode("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decoding non-json is rejected`() {
        assertThatThrownBy { TransactionTemplateCodec.decode("not json at all") }
            .isInstanceOf(JSONException::class.java)
    }

    @Test
    fun `a corrupt amount is reported rather than silently read as zero`() {
        assertThatThrownBy { TransactionTemplateCodec.decode("""{"amount":"abc"}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a corrupt amount nested in an otherwise valid payload still fails`() {
        assertThatThrownBy {
            TransactionTemplateCodec.decode(
                """{"version":1,"debit_account_uid":"a","amount":"1,5"}"""
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `isUsable requires two distinct accounts and a healthy payload`() {
        val ok = TransactionTemplate(debitAccountUid = "a", creditAccountUid = "b")
        assertThat(ok.isUsable).isTrue()

        // Same account on both sides would produce a zero-sum, meaningless pair.
        val same = TransactionTemplate(debitAccountUid = "a", creditAccountUid = "a")
        assertThat(same.isUsable).isFalse()

        // A row whose payload failed to decode must never be applied.
        val broken = TransactionTemplate(debitAccountUid = "a", creditAccountUid = "b", decodeFailed = true)
        assertThat(broken.isUsable).isFalse()

        val half = TransactionTemplate(debitAccountUid = "a")
        assertThat(half.isUsable).isFalse()
    }

    @Test
    fun `an amount with many decimals round trips exactly`() {
        val amount = BigDecimal("0.000000001")
        val decoded = TransactionTemplateCodec.decode(
            TransactionTemplateCodec.encode(TransactionTemplate(name = "tiny", amount = amount))
        )
        assertThat(decoded.amount).isEqualByComparingTo(amount)
    }
}
