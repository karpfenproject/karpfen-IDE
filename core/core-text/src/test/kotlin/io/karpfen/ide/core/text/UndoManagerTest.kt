package io.karpfen.ide.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class UndoManagerTest {

    private val time = TestTimeSource()
    private val text = EditableText("", time)
    private val undo = text.undo
    private val current: String get() = text.snapshot.value.text

    /** Types [chars] one keystroke at a time starting at [at], with carets before and after every keystroke. */
    private fun type(chars: String, at: Int = current.length) {
        var offset = at
        var index = 0
        while (index < chars.length) {
            val length = Character.charCount(chars.codePointAt(index))
            text.apply(
                listOf(TextEdit.insert(offset, chars.substring(index, index + length))),
                EditOrigin.Typing,
                caretsBefore = listOf(CaretMemento.at(offset)),
                caretsAfter = listOf(CaretMemento.at(offset + length)),
            )
            offset += length
            index += length
        }
    }

    private fun backspace(times: Int, from: Int = current.length) {
        for (caret in from downTo from - times + 1) {
            text.apply(listOf(TextEdit.delete(caret - 1, caret)), EditOrigin.Typing, listOf(CaretMemento.at(caret)), listOf(CaretMemento.at(caret - 1)))
        }
    }

    private fun forwardDelete(times: Int, at: Int) {
        repeat(times) { text.apply(listOf(TextEdit.delete(at, at + 1)), EditOrigin.Typing, listOf(CaretMemento.at(at)), listOf(CaretMemento.at(at))) }
    }

    /** Undoes until nothing is left and returns the text after every undo. */
    private fun undoAll(): List<String> = generateSequence { undo.undo()?.let { current } }.toList()

    @Test
    fun `contiguous typing within the window is one step`() {
        type("hello")
        time += 999.milliseconds
        type("!")

        assertEquals(listOf(""), undoAll())
    }

    @Test
    fun `a pause of exactly the window still coalesces, a longer one starts a new step`() {
        type("ab")
        time += EditableText.COALESCE_WINDOW_MS.milliseconds
        type("c")
        time += 1001.milliseconds
        type("d")

        assertEquals(listOf("abc", ""), undoAll())
    }

    @Test
    fun `typing at another place starts a new step`() {
        type("ab")
        type("x", at = 0)
        type("y", at = 3)

        assertEquals(listOf("xab", "ab", ""), undoAll())
    }

    @Test
    fun `a typed newline starts a new step that the next line's typing continues`() {
        type("foo\nbar")

        assertEquals(listOf("foo", ""), undoAll())
    }

    @Test
    fun `whitespace typed after a word character starts a new step`() {
        type("hello world")

        assertEquals(listOf("hello", ""), undoAll())
    }

    @Test
    fun `whitespace after punctuation or whitespace coalesces`() {
        type("a;  \tb")

        assertEquals(listOf(""), undoAll())
    }

    @Test
    fun `backspace and delete runs coalesce, typing and deleting do not`() {
        text.apply(listOf(TextEdit.insert(0, "abcdefgh")), EditOrigin.Paste)
        backspace(3)
        forwardDelete(2, at = 0)
        type("x", at = 0)

        assertEquals(listOf("cde", "abcde", "abcdefgh", ""), undoAll())
    }

    @Test
    fun `a deletion that is not contiguous starts a new step`() {
        text.apply(listOf(TextEdit.insert(0, "abcdef")), EditOrigin.Paste)
        backspace(1)
        backspace(1, from = 2)

        assertEquals(listOf("abcde", "abcdef", ""), undoAll())
    }

    @Test
    fun `paste, cut, command, external and programmatic changes never coalesce`() {
        val origins = listOf(EditOrigin.Paste, EditOrigin.Cut, EditOrigin.Command("dup"), EditOrigin.External, EditOrigin.Programmatic("gen"))
        for (origin in origins) {
            val other = EditableText("", time)
            other.apply(listOf(TextEdit.insert(0, "a")), origin)
            other.apply(listOf(TextEdit.insert(1, "b")), origin)
            other.apply(listOf(TextEdit.insert(2, "c")), EditOrigin.Typing)

            assertNotNull(other.undo.undo())
            assertEquals("ab", other.snapshot.value.text, "$origin")
            assertNotNull(other.undo.undo())
            assertEquals("a", other.snapshot.value.text, "$origin")
        }
    }

    @Test
    fun `multi-character typing and replacements are steps of their own`() {
        text.apply(listOf(TextEdit.insert(0, "()")), EditOrigin.Typing)
        type("x", at = 1)
        text.apply(listOf(TextEdit.replace(TextRange(1, 2), "y")), EditOrigin.Typing)
        type("z", at = 2)

        assertEquals(listOf("(y)", "(x)", "()", ""), undoAll())
    }

    @Test
    fun `surrogate pairs are typed and deleted as one character`() {
        type("a😀b")
        backspace(1)
        text.apply(listOf(TextEdit.delete(1, 3)), EditOrigin.Typing)

        assertEquals("a", current)
        assertEquals(listOf("a😀b", ""), undoAll())
    }

    @Test
    fun `breakCoalescing starts a new step`() {
        type("ab")
        undo.breakCoalescing()
        type("c")

        assertEquals(listOf("ab", ""), undoAll())
    }

    @Test
    fun `a group makes any sequence of changes one step, undone and redone as one change each`() {
        type("x")
        val versionBefore = text.snapshot.value.version

        undo.group("toggle comment") {
            text.apply(listOf(TextEdit.insert(0, "// ")), EditOrigin.Command("toggle comment"))
            type("y")
            text.apply(listOf(TextEdit.delete(1, 3)), EditOrigin.Cut)
        }

        assertEquals("/xy", current)
        val undone = assertNotNull(undo.undo())
        assertEquals("x", current)
        assertEquals(versionBefore + 4, undone.change.version, "one change for the whole group")
        val redone = assertNotNull(undo.redo())
        assertEquals("/xy", current)
        assertEquals(versionBefore + 5, redone.change.version)
    }

    @Test
    fun `nested groups form one step and typing does not coalesce into or after a group`() {
        type("a")
        val result = undo.group("outer") {
            type("b")
            undo.group("inner") { type("c") }
            type("d")
            42
        }
        type("e")

        assertEquals(42, result)
        assertEquals(listOf("abcd", "a", ""), undoAll())
    }

    @Test
    fun `a group is closed when its block throws`() {
        assertFailsWith<IllegalStateException> {
            undo.group("failing") {
                type("ab")
                error("boom")
            }
        }
        text.apply(listOf(TextEdit.insert(2, "c")), EditOrigin.Paste)

        assertEquals(listOf("ab", ""), undoAll())
    }

    @Test
    fun `a group that applies nothing or cancels out records nothing`() {
        type("a")
        undo.markSavePoint()
        undo.group("empty") { }
        undo.group("cancelling") {
            text.apply(listOf(TextEdit.insert(1, "xy")), EditOrigin.Command("insert"))
            text.apply(listOf(TextEdit.delete(1, 3)), EditOrigin.Command("delete"))
        }

        assertTrue(undo.isAtSavePoint.value, "the text is back at the saved state")
        assertEquals(listOf(""), undoAll())
    }

    @Test
    fun `a group continues with a new step after its changes cancelled out`() {
        undo.group("mixed") {
            text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Command("a"))
            text.apply(listOf(TextEdit.delete(0, 1)), EditOrigin.Command("b"))
            text.apply(listOf(TextEdit.insert(0, "z")), EditOrigin.Command("c"))
            text.apply(listOf(TextEdit.insert(1, "z")), EditOrigin.Command("d"))
        }

        assertEquals(listOf(""), undoAll())
    }

    @Test
    fun `a coalesced run restores the first caretsBefore on undo and the last caretsAfter on redo`() {
        type("abc")

        assertEquals(listOf(CaretMemento.at(0)), assertNotNull(undo.undo()).carets)
        assertEquals(listOf(CaretMemento.at(3)), assertNotNull(undo.redo()).carets)
    }

    @Test
    fun `a group keeps the first change's carets before and the last change's carets after, missing ones stay missing`() {
        text.apply(listOf(TextEdit.insert(0, "abc")), EditOrigin.Paste)
        undo.group("edit") {
            text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Command("1"), caretsBefore = listOf(CaretMemento(1, 2)), caretsAfter = listOf(CaretMemento.at(9)))
            text.apply(listOf(TextEdit.insert(0, "y")), EditOrigin.Command("2"), caretsBefore = listOf(CaretMemento.at(7)))
        }

        val undone = assertNotNull(undo.undo())
        assertEquals(listOf(CaretMemento(1, 2)), undone.carets)
        assertEquals("abc", current)
        assertNull(assertNotNull(undo.redo()).carets, "the last change recorded no carets after")
        undo.undo()
        assertNull(assertNotNull(undo.undo()).carets, "the paste recorded no carets")
    }

    @Test
    fun `undo and redo restore the text byte for byte and report their origin`() {
        text.apply(listOf(TextEdit.insert(0, "line1\r\nä😀")), EditOrigin.Paste)
        text.apply(listOf(TextEdit.replace(TextRange(2, 9), "-")), EditOrigin.Command("replace"))

        val undone = assertNotNull(undo.undo())
        assertEquals("line1\r\nä😀", current)
        assertEquals(EditOrigin.Undo, undone.change.origin)
        val redone = assertNotNull(undo.redo())
        assertEquals("li-\uDE00", current)
        assertEquals(EditOrigin.Redo, redone.change.origin)
    }

    @Test
    fun `undo and redo with nothing to do return null`() {
        assertNull(undo.undo())
        assertNull(undo.redo())
        type("a")
        assertNull(undo.redo())
    }

    @Test
    fun `canUndo and canRedo follow the history and a new change clears redo`() {
        assertFalse(undo.canUndo.value)
        type("a")
        assertTrue(undo.canUndo.value)
        assertFalse(undo.canRedo.value)

        undo.undo()
        assertFalse(undo.canUndo.value)
        assertTrue(undo.canRedo.value)

        type("b")
        assertTrue(undo.canUndo.value)
        assertFalse(undo.canRedo.value)
        assertNull(undo.redo())
        assertEquals("b", current)
    }

    @Test
    fun `the save point is reached again through undo and redo`() {
        assertTrue(undo.isAtSavePoint.value, "a new text is at its save point")
        text.apply(listOf(TextEdit.insert(0, "a")), EditOrigin.Paste)
        text.apply(listOf(TextEdit.insert(1, "b")), EditOrigin.Paste)
        assertFalse(undo.isAtSavePoint.value)
        undo.markSavePoint()
        assertTrue(undo.isAtSavePoint.value)

        undo.undo()
        assertFalse(undo.isAtSavePoint.value)
        undo.undo()
        assertFalse(undo.isAtSavePoint.value)
        undo.redo()
        undo.redo()
        assertTrue(undo.isAtSavePoint.value)
    }

    @Test
    fun `a save point on a discarded redo branch is never reached again`() {
        text.apply(listOf(TextEdit.insert(0, "a")), EditOrigin.Paste)
        undo.markSavePoint()
        undo.undo()
        text.apply(listOf(TextEdit.insert(0, "a")), EditOrigin.Paste)

        assertFalse(undo.isAtSavePoint.value, "same text, but a different state")
        undo.undo()
        assertFalse(undo.isAtSavePoint.value)
        undo.redo()
        assertFalse(undo.isAtSavePoint.value)
    }

    @Test
    fun `markSavePoint ends the typing run so the saved state stays reachable`() {
        type("ab")
        undo.markSavePoint()
        type("c")

        undo.undo()
        assertEquals("ab", current)
        assertTrue(undo.isAtSavePoint.value)
    }

    @Test
    fun `invalidateSavePoint leaves no state at the save point until the next markSavePoint`() {
        text.apply(listOf(TextEdit.insert(0, "a")), EditOrigin.Paste)
        undo.markSavePoint()
        text.apply(listOf(TextEdit.insert(1, "b")), EditOrigin.Paste)

        undo.invalidateSavePoint()
        assertFalse(undo.isAtSavePoint.value)
        undo.undo()
        assertFalse(undo.isAtSavePoint.value, "the formerly saved state is not the save point any more")
        undo.undo()
        assertFalse(undo.isAtSavePoint.value, "nor is the initial state")
        undo.redo()
        undo.redo()
        assertFalse(undo.isAtSavePoint.value)
        undo.clear()
        assertFalse(undo.isAtSavePoint.value, "clear keeps the save point invalid")

        undo.markSavePoint()
        assertTrue(undo.isAtSavePoint.value)
    }

    @Test
    fun `clear forgets the history and keeps the save point only if it is the current state`() {
        type("a")
        undo.markSavePoint()
        undo.clear()
        assertFalse(undo.canUndo.value)
        assertTrue(undo.isAtSavePoint.value)

        type("b")
        undo.clear()
        assertFalse(undo.isAtSavePoint.value)
        assertEquals("ab", current, "clear does not change the text")
        assertNull(undo.undo())
    }

    @Test
    fun `undo, redo and clear are not allowed inside a group`() {
        type("a")
        undo.group("busy") {
            assertFailsWith<IllegalStateException> { undo.undo() }
            assertFailsWith<IllegalStateException> { undo.redo() }
            assertFailsWith<IllegalStateException> { undo.clear() }
        }
        assertEquals(listOf(""), undoAll())
    }
}
