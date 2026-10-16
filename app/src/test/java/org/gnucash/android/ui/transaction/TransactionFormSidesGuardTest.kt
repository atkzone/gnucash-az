package org.gnucash.android.ui.transaction

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * The two halves of the "both rows show the same account" fix that no test of the
 * pieces can reach.
 *
 * [TransactionSidesTest] proves the *reading* rule — each side comes from its own
 * split type. What it cannot prove is that the form actually uses that rule, that
 * both rows are filled from one shared condition, or that "same account on both
 * sides" is refused at save time. Remove any of the three and the form still looks
 * perfectly fine while quietly storing the wrong pair: exactly the reported bug.
 *
 * Pure JVM: it reads one source file, so it needs no device and runs in the fast
 * pass.
 */
class TransactionFormSidesGuardTest {

    private fun sourceText(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            for (candidate in candidates) {
                val file = File(dir, candidate.path)
                if (file.isFile) return file.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `each row is filled from its own split type`() {
        val form = sourceText(FORM)
        assertThat(form)
            .`as`("贷方要用 CREDIT 分录，不能用「不是起始账户的那一条」")
            .contains("val transferUID = TransactionSides.creditAccountUid(transaction)")
        assertThat(form)
            .`as`("借方同理")
            .contains("TransactionSides.debitAccountUid(transaction)")
    }

    @Test
    fun `both rows and both pickers share one condition`() {
        val form = sourceText(FORM)
        val uses = "AccountConditions.selectable()".toRegex().findAll(form).count()
        assertThat(uses)
            .`as`("两个下拉 + 两个模糊搜索选择器必须同源，否则选择器会提供下拉拒绝的账户")
            .isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `saving with the same account on both sides is refused`() {
        val form = sourceText(FORM)
        assertThat(form)
            .`as`("保存前必须比较两侧账户")
            .contains("getDebitAccount(binding)?.uid == getTransferAccount(binding)?.uid")
        assertThat(form)
            .`as`("拒绝时要说得出原因")
            .contains("R.string.toast_transaction_sides_must_differ")
    }

    companion object {
        private const val FORM =
            "src/main/java/org/gnucash/android/ui/transaction/TransactionFormFragment.kt"
    }
}
