package org.gnucash.android.ui.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a bulk database write away from the main thread and answers on it.
 *
 * A bulk action touches every ticked row, and each row goes through
 * `addRecord`/`deleteRecord` — the model path that also re-derives the tag
 * mirror. Doing that inline in the dialog-result callback means the delay grows
 * with the size of the selection, so "全选 → 改值 → 保存" froze the screen for as
 * long as the batch took.
 *
 * Extracted as its own class (rather than a bare `launch` inside the ViewModel)
 * so the two things that actually matter can be asserted without a database:
 * **the block never runs on the caller's thread**, and the answer is delivered
 * afterwards.
 */
class BulkActionRunner(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate
) {

    /** @param onDone invoked on [main] with whatever [block] returned. */
    fun <T> run(block: () -> T, onDone: (T) -> Unit) {
        scope.launch {
            val result = withContext(io) { block() }
            withContext(main) { onDone(result) }
        }
    }
}
