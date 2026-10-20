package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.editor.model.caret.CaretMovement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Caret movement actions, select all and the UI requests (spec §7.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationAndUiActionsTest {

    @Test
    fun `every movement action moves the caret like the caret model`() {
        val movements = mapOf(
            EditorActionIds.CARET_LEFT to CaretMovement.LEFT, EditorActionIds.CARET_RIGHT to CaretMovement.RIGHT,
            EditorActionIds.CARET_UP to CaretMovement.UP, EditorActionIds.CARET_DOWN to CaretMovement.DOWN,
            EditorActionIds.CARET_WORD_LEFT to CaretMovement.WORD_LEFT,
            EditorActionIds.CARET_WORD_RIGHT to CaretMovement.WORD_RIGHT,
            EditorActionIds.CARET_LINE_START to CaretMovement.LINE_START,
            EditorActionIds.CARET_LINE_END to CaretMovement.LINE_END,
            EditorActionIds.CARET_PAGE_UP to CaretMovement.PAGE_UP, EditorActionIds.CARET_PAGE_DOWN to CaretMovement.PAGE_DOWN,
            EditorActionIds.CARET_DOCUMENT_START to CaretMovement.DOCUMENT_START,
            EditorActionIds.CARET_DOCUMENT_END to CaretMovement.DOCUMENT_END,
        )
        val start = (0 until 12).joinToString("\n") { "    line $it.x" }.replaceFirst("line 5", "line|5")
        for ((id, movement) in movements) {
            for (extend in listOf(false, true)) {
                val actionId = if (extend) "$id.select" else id
                val byAction = TestEditor(start).apply { pageLines = 4 }
                assertTrue(byAction.run(actionId), actionId)
                val byModel = TestEditor(start)
                byModel.caret.move(movement, extend, pageLines = 4)
                assertEquals(byModel.marked(), byAction.marked(), actionId)
            }
        }
    }

    @Test
    fun `select all selects the whole text`() {
        val editor = TestEditor("ab|\ncd")
        assertTrue(editor.run(EditorActionIds.SELECT_ALL))
        assertEquals("^ab\ncd|", editor.marked())
    }

    @Test
    fun `movement actions do not change the text`() {
        val editor = TestEditor("ab|\ncd")
        defaultEditorActions().filter { it is CaretMoveAction || it is SelectAllAction }.forEach { editor.run(it.id) }
        assertEquals(0L, editor.text.snapshot.value.version)
    }

    @Test
    fun `ui request actions forward to the ui port`() {
        val editor = TestEditor("a|b")
        val requests = listOf(
            EditorActionIds.FIND to "openFind", EditorActionIds.REPLACE to "openReplace",
            EditorActionIds.FIND_NEXT to "findNext", EditorActionIds.FIND_PREVIOUS to "findPrevious",
            EditorActionIds.GO_TO_LINE to "openGoToLine", EditorActionIds.COMPLETION to "triggerCompletion",
            EditorActionIds.GO_TO_DEFINITION to "goToDefinition",
        )
        requests.forEach { (id, _) -> assertTrue(editor.run(id), id) }
        assertEquals(requests.map { it.second }, editor.ui.requests)
        assertEquals("a|b", editor.marked(), "requests never move the caret themselves")
    }

    @Test
    fun `an external change before the caret collector ran is seen by the next action`() {
        val dispatcher = StandardTestDispatcher()
        val editor = TestEditor("alpha beta gamma|", scope = CoroutineScope(dispatcher))
        editor.text.apply(listOf(TextEdit.delete(0, 11)), EditOrigin.External)
        assertEquals(0L, editor.caret.state.value.version, "the collector has not run")

        assertTrue(editor.run(EditorActionIds.BACKSPACE))
        assertEquals("gamm|", editor.marked())
        assertTrue(editor.run(EditorActionIds.CARET_LINE_START))
        assertEquals("|gamm", editor.marked())

        editor.text.replaceAll("x", EditOrigin.External)
        assertTrue(editor.run(EditorActionIds.SELECT_LEFT))
        assertEquals("|x^", editor.marked(), "the caret was mapped behind the replacement first")
        dispatcher.scheduler.runCurrent()
        assertEquals(editor.text.snapshot.value.version, editor.caret.state.value.version)
    }
}
