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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.TestEditor
import io.karpfen.ide.ui.editor.model.caret.CaretMovement
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The completion session: triggers, requests, filtering, keys, accept and close rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionSessionModelTest {

    /**
     * A completion source that answers [labels] for the identifier before the offset (its replace range) and suspends
     * while [gate] is open (not completed).
     */
    private class FakeSource : CompletionSource {
        val requests = mutableListOf<Pair<TextSnapshot, Int>>()
        var cancelled = 0
        var gate: CompletableDeferred<Unit>? = null
        var labels: List<String> = listOf("print", "prop", "parse_rule", "has")
        var failure: Exception? = null
        var itemFor: (String, TextRange) -> CompletionItem = { label, range ->
            CompletionItem(label, CompletionKind.KEYWORD, label, range, null, null, null, label)
        }

        override suspend fun items(snapshot: TextSnapshot, offset: Int): List<CompletionItem> {
            requests += snapshot to offset
            try {
                gate?.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
            failure?.let { throw it }
            var start = offset
            while (start > 0 && WordBoundaries.isIdentifierChar(snapshot[start - 1])) start--
            return labels.map { itemFor(it, TextRange(start, offset)) }
        }
    }

    /** An editor with a completion model on the test's scheduler; [close] cancels the model's scope. */
    private class Fixture(scope: TestScope, marked: String) {
        private val job = Job()
        val editor = TestEditor(marked)
        val source = FakeSource()
        val executed = mutableListOf<EditorAction>()
        val executor = EditorActionExecutor { action ->
            executed += action
            action.isEnabled(editor) && action.execute(editor)
        }
        val completion = CompletionSessionModel(
            editor.text, editor.caret, source, executor, CoroutineScope(StandardTestDispatcher(scope.testScheduler) + job),
        )

        val labels: List<String>? get() = completion.state.value?.items?.map { it.label }

        /** Types [typed] like the editor state: the typing action, then the model's auto-trigger and filter. */
        fun type(typed: String) {
            editor.type(typed)
            completion.onTyped(typed)
        }

        fun close() = job.cancel()
    }

    private fun completionTest(marked: String, block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this, marked)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun key(code: KeyCode, vararg modifiers: KeyModifier) = KeyStroke(code, modifiers.toSet())

    @Test
    fun `an explicit request shows the filtered and ranked items`() = completionTest("x pr|") { f ->
        f.completion.open()
        assertNull(f.completion.state.value, "nothing before the source answered")
        advanceUntilIdle()

        val state = assertNotNull(f.completion.state.value)
        assertEquals(listOf("print", "prop", "parse_rule"), f.labels)
        assertEquals(0, state.selectedIndex)
        assertEquals(TextRange(2, 4), state.replaceRange)
        assertEquals("pr", state.filterText)
        assertEquals(listOf(f.editor.text.snapshot.value to 4), f.source.requests.map { it.first to it.second })
    }

    @Test
    fun `opening without items keeps completion closed`() = completionTest("x|") { f ->
        f.source.labels = emptyList()
        f.completion.open()
        advanceUntilIdle()
        assertNull(f.completion.state.value)
        f.source.labels = listOf("zzz")
        f.completion.open()
        advanceUntilIdle()
        assertNull(f.completion.state.value, "items exist, but none matches the typed x")
    }

    @Test
    fun `an identifier typed after an identifier character opens completion`() = completionTest("|") { f ->
        f.type("p")
        advanceUntilIdle()
        assertTrue(f.source.requests.isEmpty(), "one character is not enough")
        f.type("r")
        advanceUntilIdle()
        assertEquals(1, f.source.requests.size)
        assertEquals(listOf("print", "prop", "parse_rule"), f.labels)
    }

    @Test
    fun `a quote, a path start and an arrow open completion`() = completionTest("|") { f ->
        f.source.labels = listOf("robot")
        f.type("\"")
        advanceUntilIdle()
        assertEquals(1, f.source.requests.size, "the opening quote")
        assertEquals(listOf("robot"), f.labels, "the empty string content filters nothing")
        f.completion.close()

        f.editor.caret.move(CaretMovement.DOCUMENT_END, extendSelection = false)
        f.type(" $")
        f.type("(")
        advanceUntilIdle()
        assertEquals(2, f.source.requests.size, "\$(")
        f.completion.close()

        f.editor.caret.move(CaretMovement.DOCUMENT_END, extendSelection = false)
        f.type("-")
        f.type(">")
        advanceUntilIdle()
        assertEquals(3, f.source.requests.size, "->")
        f.type(" ")
        advanceUntilIdle()
        assertEquals(3, f.source.requests.size, "a space triggers nothing")
    }

    @Test
    fun `typing filters the open session and closes it when nothing matches`() = completionTest("pr|") { f ->
        f.completion.open()
        advanceUntilIdle()
        f.completion.moveSelection(1)

        f.type("o")
        assertEquals(listOf("prop"), f.labels)
        assertEquals(0, f.completion.state.value?.selectedIndex, "a new filter selects the best match")
        assertEquals(TextRange(0, 3), f.completion.state.value?.replaceRange)
        assertEquals(1, f.source.requests.size, "an open session filters without a new request")

        f.type("x")
        assertNull(f.completion.state.value)
    }

    @Test
    fun `a space or a brace typed after the word closes the session, so Enter and Tab stay the editor's`() = completionTest("|") { f ->
        f.source.labels = listOf("DO { }", "DOUBLE")
        f.type("D")
        f.type("O")
        advanceUntilIdle()
        assertEquals(listOf("DO { }", "DOUBLE"), f.labels)

        f.type(" ")
        assertNull(f.completion.state.value, "a space ends the word being completed, though the snippet's label goes on")
        f.type("{")
        advanceUntilIdle()
        assertNull(f.completion.state.value, "and the brace opens nothing")
        assertFalse(f.completion.handleKey(key(KeyCode.ENTER)), "Enter is the editor's")
        assertFalse(f.completion.handleKey(key(KeyCode.TAB)), "Tab is the editor's")
        assertEquals("DO {|}", f.editor.marked())
    }

    @Test
    fun `results for an older version are dropped and re-requested`() = completionTest("pr|") { f ->
        val gate = CompletableDeferred<Unit>()
        f.source.gate = gate
        f.completion.open()
        advanceUntilIdle()
        f.editor.text.apply(listOf(TextEdit.insert(0, "x ")), EditOrigin.External)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0L, 1L), f.source.requests.map { it.first.version })
        assertEquals(4, f.source.requests.last().second, "re-requested at the mapped caret")
        val state = assertNotNull(f.completion.state.value)
        assertEquals(TextRange(2, 4), state.replaceRange, "ranges of the current text")
    }

    @Test
    fun `an outdated result is not re-requested after the word ended`() = completionTest("|") { f ->
        val gate = CompletableDeferred<Unit>()
        f.source.gate = gate
        f.type("p")
        f.type("r")
        advanceUntilIdle()
        f.type(" ")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, f.source.requests.size, "dropped, not repeated after the space")
        assertNull(f.completion.state.value)

        f.editor.caret.moveTo(2)
        f.source.gate = CompletableDeferred()
        f.completion.open()
        advanceUntilIdle()
        f.editor.run(EditorActionIds.ENTER)
        f.source.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, f.source.requests.size, "dropped after a line break as well")
        assertNull(f.completion.state.value)
        assertFalse(f.completion.handleKey(key(KeyCode.ENTER)), "Enter is the editor's again")
    }

    @Test
    fun `a newer request cancels an older one`() = completionTest("pr|") { f ->
        val gate = CompletableDeferred<Unit>()
        f.source.gate = gate
        f.completion.open()
        advanceUntilIdle()
        f.completion.open()
        advanceUntilIdle()
        assertEquals(1, f.source.cancelled)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, f.source.requests.size)
        assertNotNull(f.completion.state.value)
    }

    @Test
    fun `the session closes when the caret leaves the range or selects`() = completionTest("pr| rest") { f ->
        f.completion.open()
        advanceUntilIdle()
        f.editor.caret.move(CaretMovement.LEFT, extendSelection = false)
        advanceUntilIdle()
        assertEquals("p", f.completion.state.value?.filterText, "moving inside the range re-filters")
        assertEquals(listOf("parse_rule", "print", "prop"), f.labels, "all are prefix matches now")

        f.editor.caret.moveTo(6)
        advanceUntilIdle()
        assertNull(f.completion.state.value, "outside the range")

        f.editor.caret.moveTo(2)
        f.completion.open()
        advanceUntilIdle()
        f.editor.caret.select(0, 2)
        advanceUntilIdle()
        assertNull(f.completion.state.value, "a selection closes completion")
    }

    @Test
    fun `keys and accept never act on a session the caret already left`() = completionTest("pr| rest") { f ->
        f.completion.open()
        advanceUntilIdle()
        f.editor.caret.moveTo(7)
        assertNotNull(f.completion.state.value, "the caret observer has not run yet")

        assertFalse(f.completion.accept())
        assertEquals("pr rest", f.editor.plain)
        assertNull(f.completion.state.value)

        f.editor.caret.moveTo(2)
        f.completion.open()
        advanceUntilIdle()
        f.editor.caret.moveTo(7)
        assertFalse(f.completion.handleKey(key(KeyCode.ENTER)), "Enter goes to the editor")
        assertEquals("pr rest", f.editor.plain)
    }

    @Test
    fun `escape and focus loss close the session and cancel a pending request`() = completionTest("pr|") { f ->
        f.completion.open()
        advanceUntilIdle()
        assertTrue(f.completion.handleKey(key(KeyCode.ESCAPE)))
        assertNull(f.completion.state.value)
        assertFalse(f.completion.handleKey(key(KeyCode.ESCAPE)), "nothing to consume when closed")

        f.completion.open()
        advanceUntilIdle()
        f.completion.close()
        assertNull(f.completion.state.value)

        val gate = CompletableDeferred<Unit>()
        f.source.gate = gate
        f.completion.open()
        advanceUntilIdle()
        f.completion.close()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(f.completion.state.value, "a closed session does not open late")
        assertEquals(1, f.source.cancelled)
    }

    @Test
    fun `keys move the selection, accept and close while open`() = completionTest("p|") { f ->
        f.source.labels = (0 until 20).map { "p%02d".format(it) }
        assertFalse(f.completion.handleKey(key(KeyCode.DOWN)), "closed: keys go to the editor")
        f.completion.open()
        advanceUntilIdle()

        fun selected() = f.completion.state.value?.selectedIndex
        assertTrue(f.completion.handleKey(key(KeyCode.DOWN)))
        assertEquals(1, selected())
        assertTrue(f.completion.handleKey(key(KeyCode.UP)))
        assertTrue(f.completion.handleKey(key(KeyCode.UP)))
        assertEquals(0, selected(), "clamped at the top")
        val page = CompletionUiState.MAX_VISIBLE_ROWS
        assertEquals(12, page, "the popup's 12 rows")
        f.completion.handleKey(key(KeyCode.PAGE_DOWN))
        assertEquals(page, selected(), "a page is the rows the popup shows")
        f.completion.handleKey(key(KeyCode.PAGE_DOWN))
        assertEquals(19, selected(), "clamped at the bottom")
        f.completion.handleKey(key(KeyCode.PAGE_UP))
        assertEquals(19 - page, selected())
        assertFalse(f.completion.handleKey(key(KeyCode.DOWN, KeyModifier.SHIFT)), "modified keys are the editor's")
        assertFalse(f.completion.handleKey(key(KeyCode.A)))
        assertFalse(f.completion.handleKey(key(KeyCode.LEFT)))

        assertTrue(f.completion.handleKey(key(KeyCode.ENTER)))
        assertEquals("p07|", f.editor.marked())
        assertNull(f.completion.state.value)
    }

    @Test
    fun `tab accepts too`() = completionTest("pr|") { f ->
        f.completion.open()
        advanceUntilIdle()
        assertTrue(f.completion.handleKey(key(KeyCode.TAB)))
        assertEquals("print|", f.editor.marked())
    }

    @Test
    fun `select highlights an item by index`() = completionTest("pr|") { f ->
        f.completion.open()
        advanceUntilIdle()
        f.completion.select(2)
        assertEquals("parse_rule", f.completion.state.value?.selected?.label)
        f.completion.select(99)
        assertEquals(2, f.completion.state.value?.selectedIndex, "an invalid index is ignored")
    }

    @Test
    fun `accept inserts over the replace range as one undo step and places the caret`() = completionTest("x pr|") { f ->
        f.source.itemFor = { label, range ->
            CompletionItem(label, CompletionKind.SNIPPET, "$label(\"\")", range, label.length + 2, null, null, label)
        }
        f.completion.open()
        advanceUntilIdle()
        f.completion.select(1)

        assertTrue(f.completion.accept())
        assertEquals("x prop(\"|\")", f.editor.marked())
        assertNull(f.completion.state.value)
        assertEquals(listOf("editor.completion.accept"), f.executed.map { it.id }, "accepted through the executor")

        f.editor.run(EditorActionIds.UNDO)
        assertEquals("x pr|", f.editor.marked())
        assertFalse(f.completion.accept(), "closed")
    }

    @Test
    fun `accept replaces the whole word under the caret`() = completionTest("pr|op x") { f ->
        f.source.itemFor = { label, range ->
            CompletionItem(label, CompletionKind.PROPERTY, label, TextRange(range.start, range.end + 2), null, null, null, label)
        }
        f.source.labels = listOf("property")
        f.completion.open()
        advanceUntilIdle()
        f.completion.accept()
        assertEquals("property| x", f.editor.marked())
    }

    @Test
    fun `accept after typing on maps the items' range into the current text`() = completionTest("pr|") { f ->
        f.completion.open()
        advanceUntilIdle()
        f.type("o")
        f.completion.accept()
        assertEquals("prop|", f.editor.marked())
    }

    @Test
    fun `a failing source keeps completion closed`() = completionTest("pr|") { f ->
        f.source.failure = IllegalStateException("broken provider")
        f.completion.open()
        advanceUntilIdle()
        assertNull(f.completion.state.value)
        f.source.failure = null
        f.completion.open()
        advanceUntilIdle()
        assertNotNull(f.completion.state.value, "the next request works again")
    }

    @Test
    fun `random typing, moves and keys keep the session consistent`() = completionTest("|") { f ->
        val random = Random(20261020)
        f.source.labels = listOf("position", "prop", "robot", "speed", "STATE", "INITIAL STATE", "x_1")
        val typed = listOf("p", "o", "s", "r", "\"", "(", "$", "-", ">", " ", "_", "1", "\n", ")")
        val keys = listOf(KeyCode.UP, KeyCode.DOWN, KeyCode.PAGE_DOWN, KeyCode.ESCAPE, KeyCode.ENTER, KeyCode.TAB)
        repeat(2_000) {
            when (random.nextInt(6)) {
                0, 1, 2 -> f.type(typed[random.nextInt(typed.size)])
                3 -> f.completion.handleKey(key(keys[random.nextInt(keys.size)]))
                4 -> f.editor.caret.moveTo(random.nextInt(0, f.editor.plain.length + 1))
                else -> if (random.nextBoolean()) f.completion.open() else f.editor.run(EditorActionIds.UNDO)
            }
            if (random.nextInt(3) != 0) return@repeat
            advanceUntilIdle()
            val state = f.completion.state.value ?: return@repeat
            val caret = f.editor.caret.primary.position
            assertTrue(caret in state.replaceRange.start..state.replaceRange.end, "the caret is inside the range")
            assertEquals(f.editor.plain.substring(state.replaceRange.start, caret), state.filterText)
            assertTrue(state.items.all { CompletionRanking.qualityOf(it.label, state.filterText) != null })
        }
    }
}
