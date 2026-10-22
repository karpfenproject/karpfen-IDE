package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.ui.editor.model.action.TestEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Find never matches on the UI thread: long-running work runs on `Dispatchers.Default` and is cancellable, so the UI
 * never waits for a search. Real threads: a single-thread "UI" dispatcher runs the editor's scope and
 * `Dispatchers.Default` the background search, as in the app. The query is a catastrophic-backtracking regular
 * expression whose search takes seconds on this short text, so the background result is not published yet when the find
 * bar's Enter, Replace or Replace All arrive; the search gives up at its time budget.
 */
class FindOffUiThreadTest {

    private val ui = Executors.newSingleThreadExecutor { Thread(it, "test-ui").apply { isDaemon = true } }
    private val scope = CoroutineScope(ui.asCoroutineDispatcher() + SupervisorJob())

    private val slowText = "|" + "a".repeat(40) + "!"
    private val slowQuery = FindQuery("(.*a){8}x", regex = true)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        ui.shutdownNow()
    }

    /** Runs [block] on the UI thread; null if it did not finish within [millis]. */
    private fun <T> onUi(millis: Long, block: () -> T): T? = try {
        ui.submit(Callable(block)).get(millis, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        null
    }

    /** An editor over [slowText] with a find model created on the UI thread, the slow query set. */
    private fun slowFind(): Pair<TestEditor, FindModel> = checkNotNull(
        onUi(5_000) {
            val editor = TestEditor(slowText, scope)
            val find = FindModel(editor.text, editor.caret, editor.executor, scope, Dispatchers.Default)
            find.setQuery(slowQuery)
            editor to find
        },
    )

    private fun assertReturnsAtOnce(operation: String, block: () -> Unit) {
        val returned = onUi(RETURN_MILLIS) { block(); true }
        assertEquals(true, returned, "$operation returned within $RETURN_MILLIS ms (no regex search on the calling thread)")
        assertEquals(true, onUi(500) { true }, "the UI thread stays responsive after $operation")
    }

    @Test
    fun `find next and previous on a lagging result return at once`() {
        val (_, find) = slowFind()
        assertReturnsAtOnce("next()") { find.next() }
        assertReturnsAtOnce("previous()") { find.previous() }
    }

    @Test
    fun `replace and replace all on a lagging result return at once and change nothing yet`() {
        val (editor, find) = slowFind()
        assertReturnsAtOnce("replaceCurrent()") { find.replaceCurrent("b") }
        assertReturnsAtOnce("replaceAll()") { find.replaceAll("b") }
        assertEquals(slowText.removePrefix("|"), onUi(500) { editor.plain })
    }

    @Test
    fun `operations waiting for a search that exceeds its budget end without effect`() {
        val (editor, find) = checkNotNull(
            onUi(5_000) {
                val editor = TestEditor(slowText, scope)
                val find = DefaultFindModel(editor.text, editor.caret, editor.executor, scope, Dispatchers.Default, 200.milliseconds)
                find.setQuery(slowQuery)
                find.next()
                find.replaceAll("b")
                editor to find
            },
        )

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && onUi(500) { find.state.value.error } == null) Thread.sleep(20)

        val state = checkNotNull(onUi(500) { find.state.value })
        assertEquals(slowQuery, state.query)
        assertEquals(FindSearch.TOO_SLOW, state.error, "the search stopped at its budget instead of running on")
        assertTrue(state.matches.isEmpty())
        assertEquals(slowText.removePrefix("|"), onUi(500) { editor.plain }, "the waiting operations found nothing to do")
    }

    /** The time limit of the UI thread. */
    private companion object {
        /** How long an operation on the UI thread may take to return; a search on it would take far longer. */
        const val RETURN_MILLIS = 2_000L
    }
}
