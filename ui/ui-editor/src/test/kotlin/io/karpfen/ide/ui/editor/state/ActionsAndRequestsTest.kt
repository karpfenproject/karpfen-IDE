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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionContext
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.defaultEditorActions
import io.karpfen.ide.ui.editor.model.find.FindQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Action execution and the UI requests of actions. Enablement follows the carets, the text and the undo history. The
 * find, go-to-line and completion requests reach the models and the overlay state. Paste uses the editor's clipboard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionsAndRequestsTest {

    /**
     * An action that is enabled only while text is selected. The enablement of the default actions does not depend on
     * the selection, so the test needs an action of its own.
     */
    private class SelectionOnlyAction : EditorAction {
        override val id: String = "test.selectionOnly"

        override fun isEnabled(context: EditorActionContext): Boolean = context.caret.primary.hasSelection

        override fun execute(context: EditorActionContext): Boolean = true
    }

    @Test
    fun `enabled actions follow the selection and the undo history`() = runTest {
        val actions = EditorActionRegistry(defaultEditorActions() + SelectionOnlyAction())
        val harness = EditorStateHarness(this, "abc", actions = actions)
        try {
            val enabled = { harness.state.enabledActions.value }
            assertFalse(EditorActionIds.UNDO in enabled())
            assertFalse("test.selectionOnly" in enabled())
            assertTrue(EditorActionIds.PASTE in enabled(), "paste never reads the clipboard for its enablement")

            harness.state.pointerPressed(1, 2, emptySet())
            assertTrue("test.selectionOnly" in enabled(), "a selection by mouse")
            assertTrue(harness.state.isEnabled("test.selectionOnly"))
            harness.state.caret.moveTo(0)
            advanceUntilIdle()
            assertFalse("test.selectionOnly" in enabled(), "a caret move observed through the caret model")

            harness.state.type("x")
            assertTrue(EditorActionIds.UNDO in enabled())
            assertFalse(EditorActionIds.REDO in enabled())
            harness.state.execute(EditorActionIds.UNDO)
            assertTrue(EditorActionIds.REDO in enabled())
            assertFalse(EditorActionIds.UNDO in enabled())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an unknown or disabled action does nothing`() = editorTest("abc") { harness ->
        assertFalse(harness.state.execute("no.such.action"))
        assertFalse(harness.state.execute(EditorActionIds.REDO))
    }

    @Test
    fun `find seeds the query from a one-line selection and opens the bar`() = editorTest("one two one\nthree") { harness ->
        harness.state.caret.select(4, 7)

        assertTrue(harness.state.execute(EditorActionIds.FIND))
        advanceUntilIdle()

        assertEquals(FindBarMode.FIND, harness.state.overlays.findBar.value)
        assertEquals("two", harness.state.find.state.value.query.text)
        assertEquals(1L, harness.state.overlays.findFocusRequests.value)

        harness.state.caret.select(4, 15)
        harness.state.execute(EditorActionIds.REPLACE)
        advanceUntilIdle()
        assertEquals(FindBarMode.REPLACE, harness.state.overlays.findBar.value)
        assertEquals("two", harness.state.find.state.value.query.text, "a multi-line selection is no query")
    }

    @Test
    fun `find next and previous open the bar while the query is empty`() = editorTest("one two one") { harness ->
        assertTrue(harness.state.execute(EditorActionIds.FIND_NEXT))
        assertEquals(FindBarMode.FIND, harness.state.overlays.findBar.value)
        harness.state.overlays.closeFind()
        assertTrue(harness.state.execute(EditorActionIds.FIND_PREVIOUS))
        assertEquals(FindBarMode.FIND, harness.state.overlays.findBar.value)

        harness.state.find.setQuery(FindQuery("one"))
        advanceUntilIdle()
        harness.state.execute(EditorActionIds.FIND_NEXT)
        assertEquals(8, harness.state.caret.primary.selection.start)
        harness.state.execute(EditorActionIds.FIND_PREVIOUS)
        assertEquals(0, harness.state.caret.primary.selection.start)
    }

    @Test
    fun `find next right after a new query navigates instead of opening the bar`() = editorTest("one two one") { harness ->
        harness.state.find.setQuery(FindQuery("one"))
        harness.state.execute(EditorActionIds.FIND_NEXT)
        advanceUntilIdle()

        assertNull(harness.state.overlays.findBar.value, "there is a query: find next navigates")
        assertEquals(TextRange(8, 11), harness.state.caret.primary.selection, "the first match, then the next one")
    }

    @Test
    fun `find seeds the query with the options set just before`() = editorTest("one two one\nthree") { harness ->
        harness.state.find.setQuery(FindQuery("x", regex = true))
        harness.state.caret.select(4, 7)

        harness.state.execute(EditorActionIds.FIND)
        advanceUntilIdle()

        assertEquals(FindQuery("two", regex = true), harness.state.find.state.value.query, "the regex toggle is kept")
    }

    @Test
    fun `go to line opens its popup`() = editorTest("abc") { harness ->
        assertTrue(harness.state.execute(EditorActionIds.GO_TO_LINE))
        assertTrue(harness.state.overlays.goToLine.value)
    }

    @Test
    fun `copy and paste use the editor's clipboard`() = editorTest("abc") { harness ->
        harness.state.caret.select(0, 2)
        assertTrue(harness.state.execute(EditorActionIds.COPY))
        assertEquals("ab", harness.clipboard.content)
        harness.state.caret.moveTo(3)
        assertTrue(harness.state.execute(EditorActionIds.PASTE))
        assertEquals("abcab", harness.plain)

        harness.clipboard.content = null
        assertFalse(harness.state.execute(EditorActionIds.PASTE), "nothing to paste")
    }

    @Test
    fun `the page size of the actions is the number of fully visible lines`() = editorTest((0 until 50).joinToString("\n")) { harness ->
        harness.layOut(visibleLines = 7)

        harness.state.execute(EditorActionIds.CARET_PAGE_DOWN)

        assertEquals(7, harness.text.snapshot.value.lineIndex.lineOf(harness.state.caret.primary.position))
    }
}
