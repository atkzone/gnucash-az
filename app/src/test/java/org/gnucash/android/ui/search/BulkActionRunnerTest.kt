package org.gnucash.android.ui.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * The half of a bulk action that used to freeze the screen.
 *
 * A bulk write touches one row per ticked transaction, each through the model
 * path; running that inline in the dialog-result callback made "全选 → 改值 →
 * 保存" block the UI thread for the whole batch. These two assertions pin the
 * shape that fixes it: the work runs on **another** thread, and the outcome still
 * comes back — without either, the toast would report a count nobody computed.
 *
 * Deliberately not a coroutine-test-dispatcher test: the app does not ship
 * `kotlinx-coroutines-test`, and "which thread does this run on" is exactly what
 * real threads answer.
 */
class BulkActionRunnerTest {

    /** The worker the injected dispatcher handed the block to. */
    private val ioThread = AtomicReference<Thread>()

    /**
     * A dispatcher that runs the work on a thread of its own and **remembers which
     * one**, so the assertion can pin "moved to IO" by identity instead of by a
     * thread name. Daemon, so a leaked worker cannot hold the JVM open.
     */
    private fun runner(): BulkActionRunner = BulkActionRunner(
        scope = CoroutineScope(Dispatchers.Default),
        io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                val worker = Thread(block, IO_THREAD).apply { isDaemon = true }
                ioThread.set(worker)
                worker.start()
            }
        },
        main = Dispatchers.Default
    )

    @Test
    fun `the write never runs on the thread that asked for it`() {
        val caller = Thread.currentThread()
        val ranOn = AtomicReference<Thread>()
        val gate = CountDownLatch(1)

        runner().run({
            ranOn.set(Thread.currentThread())
            gate.countDown()
            1
        }) { }

        assertThat(gate.await(5, TimeUnit.SECONDS))
            .`as`("the batch has to actually run")
            .isTrue()
        assertThat(ranOn.get())
            .`as`("blocking the UI thread is what made 保存 look like a freeze")
            .isNotSameAs(caller)
        // Not just "another thread": scope.launch already runs elsewhere, so dropping
        // withContext(io) would keep the assertion above green while the UI thread got
        // blocked again. Identity with the dispatched worker pins it to the IO side.
        assertThat(ranOn.get())
            .`as`("批写必须落在注入的 IO dispatcher 派出的线程上")
            .isSameAs(ioThread.get())
    }

    @Test
    fun `the outcome is handed to the callback`() {
        val reported = AtomicReference<Int>()
        val gate = CountDownLatch(1)

        runner().run({ 42 }) {
            reported.set(it)
            gate.countDown()
        }

        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(reported.get()).isEqualTo(42)
    }

    @Test
    fun `the callback gets the real count, not the requested one`() {
        val reported = AtomicReference<Int>()
        val gate = CountDownLatch(1)

        // Two of the three uids were already gone: the user must be told 2.
        runner().run({ listOf("a", "b", "c").count { it != "b" } }) {
            reported.set(it)
            gate.countDown()
        }

        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(reported.get()).isEqualTo(2)
    }

    companion object {
        private const val IO_THREAD = "bulk-action-io"
    }
}
