package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.ui.editor.model.caret.Caret
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * "Every action is exactly one undo step" (spec §7.1) and undo/redo restore the carets (`UndoManager` rule): every
 * default action and typed text, run once in a fresh editor (no history), either changes nothing or leaves exactly one
 * undo step whose undo restores text and carets and whose redo restores the result. Checked on hand-picked states and
 * on random caret states over every example file and mutations of them (fixed seeds); no action may throw.
 */
class ActionUndoStepTest {

    private val typed = listOf("x", "(", "{", "\"", ")", "}", "\n", "ab\r\ncd", " ")

    /** Every action under test as (name, action). */
    private val actions: List<Pair<String, EditorAction>> =
        defaultEditorActions().filter { it.id != EditorActionIds.UNDO && it.id != EditorActionIds.REDO }.map { it.id to it } +
            typed.map { "type '$it'" to typeTextAction(it) }

    private val states = listOf(
        "|",
        "STATE \"a\" {|}",
        "STATE \"a\" {\n    EVAL {\n        x = 1|\n    }\n}",
        "    ^prop(\"x\", \"number\")\n    has(\"y\", \"T\")|\n",
        "a|b^c",
        "f(|)",
        "\t|\tx",
        "line1\nline2\n|",
        "^one\ntwo\n|three",
        "x = \"ab|c\"",
    )

    /**
     * Runs [action] once in a fresh editor on [text] with [caret] and checks the one-step rule; returns whether the
     * text changed.
     */
    private fun checkOneStep(name: String, action: EditorAction, text: String, caret: Caret, clipboard: String?): Boolean {
        val editor = TestEditor(text, tokensOf = ::evalBodies)
        editor.caret.restore(listOf(caret.toMemento()))
        editor.clipboard.content = clipboard
        val where = "$name at $caret"
        val caretsBefore = editor.caret.mementos()
        if (action.isEnabled(editor)) action.execute(editor)
        val textAfter = editor.plain
        val caretsAfter: List<CaretMemento> = editor.caret.mementos()
        if (textAfter == text) {
            assertFalse(editor.text.undo.canUndo.value, "$where changed nothing but left a step")
            return false
        }
        editor.text.undo.undo()?.carets?.let(editor.caret::restore)
        assertEquals(text, editor.plain, "undo of $where")
        assertEquals(caretsBefore, editor.caret.mementos(), "carets after undo of $where")
        assertFalse(editor.text.undo.canUndo.value, "$where left more than one step")
        editor.text.undo.redo()?.carets?.let(editor.caret::restore)
        assertEquals(textAfter, editor.plain, "redo of $where")
        assertEquals(caretsAfter, editor.caret.mementos(), "carets after redo of $where")
        return true
    }

    private fun checkOneStep(name: String, action: EditorAction, marked: String, clipboard: String?): Boolean {
        val parsed = Marked.parse(marked)
        return checkOneStep("$name on '$marked'", action, parsed.text, parsed.caret, clipboard)
    }

    @Test
    fun `every action is at most one undo step that restores text and carets`() {
        val changing = mutableSetOf<String>()
        for (state in states) {
            for ((name, action) in actions) {
                if (checkOneStep(name, action, state, clipboard = "pasted\r\ntext")) changing += name
            }
        }
        val textActions = listOf(
            EditorActionIds.ENTER, EditorActionIds.BACKSPACE, EditorActionIds.DELETE, EditorActionIds.DELETE_WORD_LEFT,
            EditorActionIds.DELETE_WORD_RIGHT, EditorActionIds.DELETE_LINE, EditorActionIds.DUPLICATE,
            EditorActionIds.MOVE_LINE_UP, EditorActionIds.MOVE_LINE_DOWN, EditorActionIds.INDENT, EditorActionIds.OUTDENT,
            EditorActionIds.TOGGLE_COMMENT, EditorActionIds.CUT, EditorActionIds.PASTE,
        )
        assertEquals(textActions.toSet() + typed.map { "type '$it'" }, changing.toSet(), "every editing action was exercised")
    }

    @Test
    fun `deleting an auto-closed pair is one step`() {
        val editor = TestEditor("f|")
        editor.type("(")
        val before = editor.marked()
        editor.run(EditorActionIds.BACKSPACE)
        assertEquals("f|", editor.marked())
        editor.run(EditorActionIds.UNDO)
        assertEquals(before, editor.marked())
    }

    @Test
    fun `actions never throw and keep the one-step rule on random states of every example`() {
        val random = Random(20261020)
        val texts = Examples.files().map { it.path to it.text } +
            Examples.files().take(3).flatMap { file ->
                Mutations.random(file.text, seed = 7, count = 20).map { "${file.path} #${it.index}" to it.text }
            }
        var runs = 0
        for ((path, text) in texts) {
            repeat(3) {
                val anchor = random.nextInt(0, text.length + 1)
                val position = if (random.nextBoolean()) anchor else random.nextInt(0, text.length + 1)
                for ((name, action) in actions) {
                    checkOneStep("$name in $path", action, text, Caret(anchor, position), clipboard = "x\ny")
                    runs++
                }
            }
        }
        assertFalse(runs == 0)
    }
}
