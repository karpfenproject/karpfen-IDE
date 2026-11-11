/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * Find never matches on the UI thread. Long-running work runs on `Dispatchers.Default` and can be cancelled, so the UI
 * never waits for a search. The tests use real threads, as in the app. A single-thread dispatcher stands for the UI
 * thread and runs the editor's scope, and `Dispatchers.Default` runs the background search.
 *
 * The query is a regular expression with catastrophic backtracking. Its search takes seconds on this short text, so
 * the background result is not published yet when the find bar's Enter, Replace or Replace All arrive. The search gives
 * up when it reaches its time budget.
 */
class FindOffUiThreadTest {

    /** The single thread that stands for the UI thread. */
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "test-ui").apply { isDaemon = true } }

    /** The editor's scope, which runs on [ui]. */
    private val scope = CoroutineScope(ui.asCoroutineDispatcher() + SupervisorJob())

    /** A short text in the marker notation of [TestEditor] whose search for [slowQuery] takes seconds. */
    private val slowText = "|" + "a".repeat(40) + "!"

    /** A regular expression with catastrophic backtracking on [slowText]. */
    private val slowQuery = FindQuery("(.*a){8}x", regex = true)

    /** Cancels the scope and stops the UI thread. */
    @AfterTest
    fun tearDown() {
        scope.cancel()
        ui.shutdownNow()
    }

    /** Runs [block] on the UI thread and returns its result, or null if it did not finish within [millis]. */
    private fun <T> onUi(millis: Long, block: () -> T): T? = try {
        ui.submit(Callable(block)).get(millis, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        null
    }

    /** An editor over [slowText] with a find model that was created on the UI thread and has the slow query set. */
    private fun slowFind(): Pair<TestEditor, FindModel> = checkNotNull(
        onUi(5_000) {
            val editor = TestEditor(slowText, scope)
            val find = FindModel(editor.text, editor.caret, editor.executor, scope, Dispatchers.Default)
            find.setQuery(slowQuery)
            editor to find
        },
    )

    /** Asserts that [block] returns on the UI thread within [RETURN_MILLIS] and that the thread answers again right after. */
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

    /** Holds the time limit of the UI thread. */
    private companion object {
        /** How long an operation on the UI thread may take to return. A search on that thread would take far longer. */
        const val RETURN_MILLIS = 2_000L
    }
}
