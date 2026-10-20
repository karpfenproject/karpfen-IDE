package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.editor.model.action.TestEditor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Selection, mementos, mapping through changes and undo coalescing of [CaretModel] (spec §7.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class CaretModelTest {

    @Test
    fun `a new caret model has one caret at 0 for the current version`() {
        val text = EditableText("abc")
        text.apply(listOf(TextEdit.insert(3, "d")), EditOrigin.Typing)
        val caret = CaretModel(text, TestScope())

        assertEquals(CaretSnapshot(1, listOf(Caret.at(0))), caret.current)
        assertEquals(Caret.at(0), caret.primary)
    }

    @Test
    fun `move to and select clamp to the text`() {
        val editor = TestEditor("abc")
        editor.caret.moveTo(99)
        assertEquals("abc|", editor.marked())
        editor.caret.moveTo(1, extendSelection = true)
        assertEquals("a|bc^", editor.marked())
        editor.caret.moveTo(-5)
        assertEquals("|abc", editor.marked())
        editor.caret.select(-1, 2)
        assertEquals("^ab|c", editor.marked())
        editor.caret.select(10, 1)
        assertEquals("a|bc^", editor.marked())
    }

    @Test
    fun `select word picks the run under the click`() {
        fun word(marked: String): String {
            val editor = TestEditor(marked.replace("|", ""))
            editor.caret.selectWord(marked.indexOf('|'))
            return editor.marked()
        }
        assertEquals("foo ^bar_1|(x)", word("foo ba|r_1(x)"))
        assertEquals("foo ^bar_1|(x)", word("foo |bar_1(x)"), "at the word start")
        assertEquals("foo ^bar_1|(x)", word("foo bar_1|(x)"), "right after a word: the word, not the bracket")
        assertEquals("^foo| bar", word("foo| bar"), "between a word and a space: the word")
        assertEquals("a ^->|b", word("a -|>b"), "punctuation run")
        assertEquals("a^   |b", word("a  | b"), "whitespace run")
        assertEquals("ab\n|\ncd", word("ab\n|\ncd"), "an empty line selects nothing")
        assertEquals("^ab|\ncd", word("ab|\ncd"), "at a line end: the word before")
    }

    @Test
    fun `select line includes the line break, select all everything`() {
        val editor = TestEditor("ab\ncd\nef")
        editor.caret.selectLine(1)
        assertEquals("ab\n^cd\n|ef", editor.marked())
        editor.caret.selectLine(2)
        assertEquals("ab\ncd\n^ef|", editor.marked(), "the last line has no line break")
        editor.caret.selectLine(99)
        assertEquals("ab\ncd\n^ef|", editor.marked(), "clamped to the last line")
        editor.caret.selectAll()
        assertEquals("^ab\ncd\nef|", editor.marked())
    }

    @Test
    fun `mementos and restore round-trip and clamp`() {
        val editor = TestEditor("ab^cd|ef")
        assertEquals(listOf(CaretMemento(2, 4)), editor.caret.mementos())
        editor.caret.restore(listOf(CaretMemento(5, 1)))
        assertEquals("a|bcde^f", editor.marked())
        editor.caret.restore(listOf(CaretMemento(99, 99)))
        assertEquals("abcdef|", editor.marked())
        editor.caret.restore(emptyList())
        assertEquals("abcdef|", editor.marked(), "an empty list keeps the carets")
    }

    @Test
    fun `carets follow edits made elsewhere`() {
        val editor = TestEditor("abc|def")
        editor.text.apply(listOf(TextEdit.insert(0, "XY")), EditOrigin.External)
        assertEquals("XYabc|def", editor.marked())
        editor.text.apply(listOf(TextEdit.insert(5, "!")), EditOrigin.External)
        assertEquals("XYabc!|def", editor.marked(), "text inserted at the caret goes before it")
        editor.text.apply(listOf(TextEdit.delete(1, 7)), EditOrigin.External)
        assertEquals("X|ef", editor.marked(), "a caret inside deleted text goes to the deletion point")
    }

    @Test
    fun `selections follow edits made elsewhere`() {
        val editor = TestEditor("ab^cd|ef")
        editor.text.apply(listOf(TextEdit.insert(0, "__")), EditOrigin.External)
        assertEquals("__ab^cd|ef", editor.marked())
        editor.text.replaceAll("x", EditOrigin.External)
        assertEquals("x|", editor.marked())
    }

    @Test
    fun `an external change before the collector ran is seen by the next access`() = runTest {
        val text = EditableText("hello world")
        val caret = CaretModel(text, TestScope(StandardTestDispatcher(testScheduler)))
        caret.moveTo(11)
        text.replaceAll("hi", EditOrigin.External)

        assertEquals(CaretSnapshot(0, listOf(Caret.at(11))), caret.state.value, "the state may lag")
        assertEquals(CaretSnapshot(1, listOf(Caret.at(2))), caret.current, "current catches up synchronously")
        assertEquals(1, caret.state.value.version, "catching up publishes the mapped carets")
    }

    @Test
    fun `the state follows changes for observers`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val text = EditableText("abc")
        val caret = CaretModel(text, scope)
        caret.moveTo(3)
        scope.testScheduler.runCurrent()
        text.apply(listOf(TextEdit.insert(0, "xx")), EditOrigin.External)
        assertEquals(0, caret.state.value.version, "not yet collected")
        scope.testScheduler.runCurrent()
        assertEquals(CaretSnapshot(1, listOf(Caret.at(5))), caret.state.value)
    }

    @Test
    fun `carets survive history eviction by clamping`() {
        val text = EditableText("abcdef", historyLimit = 1)
        val caret = CaretModel(text, TestScope())
        caret.moveTo(6)
        text.apply(listOf(TextEdit.delete(0, 1)), EditOrigin.External)
        text.apply(listOf(TextEdit.delete(0, 1)), EditOrigin.External)
        text.apply(listOf(TextEdit.delete(0, 1)), EditOrigin.External)

        assertEquals(3, caret.primary.position, "clamped to the shorter text")
    }

    @Test
    fun `restored carets carry the new version and are not mapped again`() {
        val editor = TestEditor("ab|")
        editor.text.apply(listOf(TextEdit.insert(2, "c")), EditOrigin.Typing, editor.caret.mementos(), listOf(CaretMemento.at(1)))
        editor.caret.restore(listOf(CaretMemento.at(1)))
        assertEquals("a|bc", editor.marked())
        assertEquals(1L, editor.caret.current.version)
    }

    @Test
    fun `explicit moves break undo coalescing, restore does not`() {
        val editor = TestEditor("|")
        editor.typeEach("ab")
        editor.caret.restore(editor.caret.mementos())
        editor.type("c")
        editor.text.undo.undo()
        assertEquals("", editor.plain, "typing after restore continued the run")

        val moved = TestEditor("|")
        moved.typeEach("ab")
        moved.caret.moveTo(2)
        moved.type("c")
        moved.text.undo.undo()
        assertEquals("ab", moved.plain, "a click between the keystrokes started a new step")
    }

    @Test
    fun `every explicit caret change breaks coalescing`() {
        val changes: List<(CaretModel) -> Unit> = listOf(
            { it.move(CaretMovement.RIGHT, extendSelection = false) },
            { it.moveTo(2) },
            { it.select(2, 2) },
            { it.selectWord(2) },
            { it.selectLine(0) },
            { it.selectAll() },
        )
        changes.forEachIndexed { index, change ->
            val editor = TestEditor("|")
            editor.typeEach("ab")
            change(editor.caret)
            editor.caret.restore(listOf(CaretMemento.at(2)))
            editor.type("c")
            editor.text.undo.undo()
            assertEquals("ab", editor.plain, "change $index")
        }
    }

    @Test
    fun `colliding carets merge`() {
        val editor = TestEditor("abc")
        editor.caret.restore(listOf(CaretMemento.at(1), CaretMemento.at(1)))
        assertEquals(1, editor.caret.current.carets.size)
        editor.caret.restore(listOf(CaretMemento.at(1), CaretMemento.at(3)))
        editor.caret.move(CaretMovement.DOCUMENT_END, extendSelection = false)
        assertEquals(listOf(Caret.at(3)), editor.caret.current.carets)
        assertTrue(editor.caret.current.carets.none { it.hasSelection })
        assertFalse(editor.caret.primary.hasSelection)
    }
}
