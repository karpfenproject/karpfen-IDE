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

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.TestEditor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Find/replace over the real text and carets, with virtual time for the background matching. */
@OptIn(ExperimentalCoroutinesApi::class)
class FindModelTest {

    /**
     * An editor with a find model whose executor records every action it runs. The model's scope and background
     * dispatcher run on the test's scheduler (foreground work, so `advanceUntilIdle` runs it); [close] cancels it.
     */
    private class Fixture(scope: TestScope, marked: String) {
        private val job = Job()
        val editor = TestEditor(marked)
        val executed = mutableListOf<EditorAction>()
        val executor = EditorActionExecutor { action ->
            executed += action
            action.isEnabled(editor) && action.execute(editor)
        }
        val find = FindModel(
            editor.text, editor.caret, executor,
            CoroutineScope(StandardTestDispatcher(scope.testScheduler) + job), StandardTestDispatcher(scope.testScheduler),
        )

        fun close() = job.cancel()

        val selection: TextRange get() = editor.caret.primary.selection
        val selected: List<TextRange> get() = executed.filterIsInstance<SelectMatchAction>().map { it.match }
        val state: FindResult get() = find.state.value
    }

    private fun findTest(marked: String, block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this, marked)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private val foo = "foo bar foo baz foo"
    private val fooMatches = listOf(TextRange(0, 3), TextRange(8, 11), TextRange(16, 19))

    @Test
    fun `setQuery selects the first match at or after the selection start`() = findTest("foo bar |foo baz foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()

        assertEquals(fooMatches, f.state.matches)
        assertEquals(1, f.state.currentIndex)
        assertEquals("2/3", "${f.state.currentNumber}/${f.state.total}")
        assertEquals(TextRange(8, 11), f.selection)
        assertEquals(listOf(TextRange(8, 11)), f.selected, "the selection went through the executor")
    }

    @Test
    fun `setQuery keeps an already selected match current`() = findTest("foo bar ^foo| baz foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()
        assertEquals(TextRange(8, 11), f.selection)
    }

    @Test
    fun `setQuery wraps to the first match when none follows the caret`() = findTest("foo bar foo baz foo |") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()
        assertEquals(TextRange(0, 3), f.selection)
    }

    @Test
    fun `the first next after setQuery moves to the following match, wrapping`() = findTest("foo bar |foo baz foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()

        f.find.next()
        assertEquals(TextRange(16, 19), f.selection)
        assertEquals(2, f.state.currentIndex)
        f.find.next()
        assertEquals(TextRange(0, 3), f.selection, "wraps to the first match")
        f.find.previous()
        assertEquals(TextRange(16, 19), f.selection, "wraps to the last match")
        f.find.previous()
        assertEquals(TextRange(8, 11), f.selection)
        assertEquals(listOf(TextRange(8, 11), TextRange(16, 19), TextRange(0, 3), TextRange(16, 19), TextRange(8, 11)), f.selected)
    }

    @Test
    fun `next with an unselected current match selects that one`() = findTest("foo bar |foo baz foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()
        f.editor.caret.moveTo(12)
        advanceUntilIdle()
        assertEquals(2, f.state.currentIndex, "the current match follows the caret")

        f.find.next()
        assertEquals(TextRange(16, 19), f.selection, "the current match is selected first")
        f.find.next()
        assertEquals(TextRange(0, 3), f.selection)
    }

    @Test
    fun `previous with an unselected current match selects that one`() = findTest("foo bar |foo baz foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()
        f.editor.caret.moveTo(5)

        f.find.previous()
        assertEquals(TextRange(8, 11), f.selection)
        f.find.previous()
        assertEquals(TextRange(0, 3), f.selection)
    }

    @Test
    fun `navigation waits for the background result when the published state lags`() = findTest("foo bar |foo baz foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        assertEquals(FindQuery("foo"), f.find.query.value, "the query is set at once")
        assertEquals(FindQuery(), f.state.query, "its matches are not computed yet")
        f.find.next()
        assertTrue(f.selected.isEmpty(), "nothing is matched on the calling thread")

        advanceUntilIdle()
        assertEquals(TextRange(16, 19), f.selection, "the same result as with the background search done first")
        assertEquals(listOf(TextRange(8, 11), TextRange(16, 19)), f.selected)
        assertEquals(2, f.state.currentIndex)
    }

    @Test
    fun `operations waiting for the matches run in call order`() = findTest("|a a a a") { f ->
        f.find.setQuery(FindQuery("a"))
        f.find.next()
        f.find.next()
        f.find.previous()

        advanceUntilIdle()
        assertEquals(listOf(TextRange(0, 1), TextRange(2, 3), TextRange(4, 5), TextRange(2, 3)), f.selected)
    }

    @Test
    fun `a new query drops the operations waiting for the old one`() = findTest("|ab ab b") { f ->
        f.find.setQuery(FindQuery("ab"))
        f.find.next()
        f.find.replaceAll("x")
        f.find.setQuery(FindQuery("b"))

        advanceUntilIdle()
        assertEquals("ab ab b", f.editor.plain, "the replacement for the old query was dropped")
        assertEquals(listOf(TextRange(1, 2)), f.selected, "only the new query's first match was selected")
    }

    @Test
    fun `next after an edit uses the matches of the current text`() = findTest("|foo foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()
        assertEquals(TextRange(0, 3), f.selection)

        f.editor.text.apply(listOf(TextEdit.insert(0, "xx")), EditOrigin.External)
        f.find.next()
        advanceUntilIdle()
        assertEquals(TextRange(6, 9), f.selection)
        assertEquals(listOf(TextRange(2, 5), TextRange(6, 9)), f.state.matches)
        assertEquals(1L, f.state.version)
    }

    @Test
    fun `matches follow the text in the background`() = findTest("x|") { f ->
        f.find.setQuery(FindQuery("x"))
        advanceUntilIdle()
        assertEquals(1, f.state.total)

        f.editor.text.apply(listOf(TextEdit.insert(1, " x x")), EditOrigin.External)
        assertEquals(0L, f.state.version, "published later")
        advanceUntilIdle()
        assertEquals(1L, f.state.version)
        assertEquals(3, f.state.total)
        assertEquals(1, f.selected.size, "a text change never selects")
    }

    @Test
    fun `without matches the selection stays and nothing happens`() = findTest("a^b|c") { f ->
        f.find.setQuery(FindQuery("zz"))
        advanceUntilIdle()

        assertEquals(0, f.state.total)
        assertNull(f.state.currentIndex)
        assertEquals(0, f.state.currentNumber)
        f.find.next()
        f.find.previous()
        f.find.replaceCurrent("y")
        f.find.replaceAll("y")
        advanceUntilIdle()
        assertEquals("a^b|c", f.editor.marked())
        assertTrue(f.executed.isEmpty())
    }

    @Test
    fun `an invalid regular expression is an error state`() = findTest("(ab|") { f ->
        f.find.setQuery(FindQuery("(ab", regex = true))
        advanceUntilIdle()
        assertNotNull(f.state.error)
        assertEquals(0, f.state.total)
        f.find.next()
        f.find.replaceAll("x")
        advanceUntilIdle()
        assertEquals("(ab", f.editor.plain)

        f.find.setQuery(FindQuery("\\(ab", regex = true))
        advanceUntilIdle()
        assertNull(f.state.error)
        assertEquals(1, f.state.total)
    }

    @Test
    fun `query options reach the matcher`() = findTest("|Foo foo food") { f ->
        val cases = mapOf(
            FindQuery("foo") to 3,
            FindQuery("foo", caseSensitive = true) to 2,
            FindQuery("foo", wholeWord = true) to 2,
            FindQuery("fo+d", regex = true) to 1,
        )
        for ((query, total) in cases) {
            f.find.setQuery(query)
            advanceUntilIdle()
            assertEquals(total, f.state.total, "$query")
            assertEquals(query, f.state.query)
        }
    }

    @Test
    fun `setting the same query again selects the match at the caret`() = findTest("|foo bar foo") { f ->
        f.find.setQuery(FindQuery("foo"))
        advanceUntilIdle()
        f.editor.caret.moveTo(5)
        f.find.setQuery(FindQuery("foo"))
        assertEquals(TextRange(8, 11), f.selection)
    }

    @Test
    fun `the replacement is kept next to the query and survives a new query`() = findTest("|a1 a2") { f ->
        assertEquals("", f.find.replacement.value)
        f.find.setReplacement("b")
        f.find.setQuery(FindQuery("a"))
        advanceUntilIdle()

        assertEquals("b", f.find.replacement.value)
        assertEquals("a1 a2", f.editor.plain, "setting the replacement replaces nothing")
    }

    @Test
    fun `replace current replaces the match and moves to the next one`() = findTest("|a1 a2 a3") { f ->
        f.find.setQuery(FindQuery("a"))
        advanceUntilIdle()

        f.find.replaceCurrent("bb")
        assertEquals("bb1 a2 a3", f.editor.plain, "a literal replacement needs no search")
        advanceUntilIdle()
        assertEquals(TextRange(4, 5), f.selection, "the next match is selected once the matches are current")
        assertEquals(listOf(TextRange(4, 5), TextRange(7, 8)), f.state.matches)

        f.editor.run(EditorActionIds.UNDO)
        assertEquals("a1 a2 a3", f.editor.plain, "one undo step")
        assertEquals(TextRange(0, 1), f.selection, "with the carets before the replacement")
    }

    @Test
    fun `replace current expands group references in regex mode`() = findTest("|a1 a2") { f ->
        f.find.setQuery(FindQuery("a(\\d)", regex = true))
        advanceUntilIdle()
        f.find.replaceCurrent("<$1>")
        advanceUntilIdle()
        assertEquals("<1> a2", f.editor.plain)
        assertEquals(TextRange(4, 6), f.selection)
    }

    @Test
    fun `replace all is one undo step`() = findTest("x a1 a2 a3|") { f ->
        f.find.setQuery(FindQuery("a(\\d)", regex = true))
        advanceUntilIdle()

        f.find.replaceAll("$1a")
        advanceUntilIdle()
        assertEquals("x 1a 2a 3a", f.editor.plain)
        assertEquals("x ^1a| 2a 3a", f.editor.marked(), "the selected first match covers its replacement")
        assertEquals(0, f.state.total)

        f.editor.run(EditorActionIds.UNDO)
        assertEquals("x a1 a2 a3", f.editor.plain)
        f.editor.run(EditorActionIds.REDO)
        assertEquals("x 1a 2a 3a", f.editor.plain)
        f.editor.run(EditorActionIds.UNDO)
        f.editor.run(EditorActionIds.UNDO)
        assertEquals("x a1 a2 a3", f.editor.plain, "nothing else was undoable")
    }

    @Test
    fun `replace all waits for the matches when the state lags`() = findTest("|aaa") { f ->
        f.find.setQuery(FindQuery("a"))
        f.find.replaceAll("b")
        assertEquals("aaa", f.editor.plain, "nothing is matched on the calling thread")
        advanceUntilIdle()
        assertEquals("bbb", f.editor.plain)
    }

    @Test
    fun `a regex replacement is expanded on the background dispatcher`() = runTest {
        val editor = TestEditor("|a1 a2")
        val background = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val job = Job()
        val find = FindModel(editor.text, editor.caret, editor.executor, CoroutineScope(StandardTestDispatcher(testScheduler) + job), background)
        find.setQuery(FindQuery("a(\\d)", regex = true))
        advanceUntilIdle()
        val searches = background.dispatches

        find.replaceAll("<$1>")
        assertEquals("a1 a2", editor.plain, "not expanded on the calling thread")
        advanceUntilIdle()
        assertTrue(background.dispatches > searches, "the expansion ran on the background dispatcher")
        assertEquals("<1> <2>", editor.plain)
        job.cancel()
    }

    @Test
    fun `a regex replacement outdated by an edit runs for the current text`() = findTest("|a1 a2") { f ->
        f.find.setQuery(FindQuery("a(\\d)", regex = true))
        advanceUntilIdle()

        f.find.replaceAll("<$1>")
        f.editor.text.apply(listOf(TextEdit.insert(0, "a0 ")), EditOrigin.External)
        advanceUntilIdle()

        assertEquals("<0> <1> <2>", f.editor.plain, "the matches of the current text were replaced")
    }

    @Test
    fun `every selection and replacement reaches the executor`() = findTest("|a a a") { f ->
        f.find.setQuery(FindQuery("a"))
        advanceUntilIdle()
        f.find.next()
        f.find.previous()
        f.find.replaceCurrent("b")
        f.find.replaceAll("c")
        advanceUntilIdle()
        val ids = f.executed.map { it.id }
        assertEquals(
            listOf(SelectMatchAction.ID, SelectMatchAction.ID, SelectMatchAction.ID, "editor.find.replace", SelectMatchAction.ID, "editor.find.replaceAll"),
            ids,
        )
    }

    /** A dispatcher that counts the blocks it runs for [delegate]. */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun `matching runs on the injected background dispatcher, the empty query on none`() = runTest {
        val editor = TestEditor("|foo")
        val background = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val job = Job()
        val find = FindModel(editor.text, editor.caret, editor.executor, CoroutineScope(StandardTestDispatcher(testScheduler) + job), background)
        advanceUntilIdle()
        assertEquals(0, background.dispatches, "the empty query of a closed find bar")

        find.setQuery(FindQuery("foo"))
        advanceUntilIdle()
        assertTrue(background.dispatches > 0)
        assertEquals(1, find.state.value.total)
        job.cancel()
    }
}
