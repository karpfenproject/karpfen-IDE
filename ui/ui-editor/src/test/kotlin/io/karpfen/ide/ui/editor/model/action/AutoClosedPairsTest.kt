package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The version-stamped tracker of auto-inserted closers, mapped on query. */
class AutoClosedPairsTest {

    private fun EditableText.edit(edit: TextEdit) = apply(listOf(edit), EditOrigin.Typing)

    @Test
    fun `a recorded pair is found by its closer and around the caret`() {
        val text = EditableText("f()")
        val pairs = AutoClosedPairs(text)
        pairs.record(1, 2)

        assertTrue(pairs.isAutoClosedCloser(2))
        assertFalse(pairs.isAutoClosedCloser(1))
        assertEquals(TextRange(1, 3), pairs.pairAround(2))
        assertNull(pairs.pairAround(1))
        assertNull(pairs.pairAround(3))
    }

    @Test
    fun `pairs follow insertions around and inside them`() {
        val text = EditableText("f()")
        val pairs = AutoClosedPairs(text)
        pairs.record(1, 2)
        text.edit(TextEdit.insert(0, "xx"))
        assertTrue(pairs.isAutoClosedCloser(4))
        text.edit(TextEdit.insert(4, "abc"))
        assertTrue(pairs.isAutoClosedCloser(7), "text typed inside the pair pushes the closer")
        assertNull(pairs.pairAround(4), "the pair is no longer directly around a caret")
        text.edit(TextEdit.insert(8, "!"))
        assertTrue(pairs.isAutoClosedCloser(7), "text after the closer does not move it")
    }

    @Test
    fun `a pair whose closer or opener was deleted or replaced is dropped`() {
        val deleted = EditableText("f()")
        val first = AutoClosedPairs(deleted)
        first.record(1, 2)
        deleted.edit(TextEdit.delete(2, 3))
        deleted.edit(TextEdit.insert(2, ")"))
        assertFalse(first.isAutoClosedCloser(2), "a retyped closer is the user's")

        val replaced = EditableText("f()")
        val second = AutoClosedPairs(replaced)
        second.record(1, 2)
        replaced.edit(TextEdit.replace(TextRange(1, 2), "("))
        assertFalse(second.isAutoClosedCloser(2), "the opener was replaced (even by the same character)")
    }

    @Test
    fun `undo and a reload drop pairs whose characters went away`() {
        val text = EditableText("f")
        val pairs = AutoClosedPairs(text)
        text.edit(TextEdit.insert(1, "()"))
        pairs.record(1, 2)
        text.undo.undo()
        assertFalse(pairs.isAutoClosedCloser(2))
        text.undo.redo()
        assertFalse(pairs.isAutoClosedCloser(2), "a dropped pair does not come back")

        val reloaded = EditableText("a()")
        val tracked = AutoClosedPairs(reloaded)
        tracked.record(1, 2)
        reloaded.replaceAll("b()", EditOrigin.External)
        assertTrue(tracked.isAutoClosedCloser(2), "a reload that kept the characters keeps the pair")
        reloaded.replaceAll("b(]", EditOrigin.External)
        assertFalse(tracked.isAutoClosedCloser(2))
    }

    @Test
    fun `pairs whose history was evicted are dropped`() {
        val text = EditableText("()", historyLimit = 1)
        val pairs = AutoClosedPairs(text)
        pairs.record(0, 1)
        text.edit(TextEdit.insert(2, "a"))
        text.edit(TextEdit.insert(3, "b"))
        assertFalse(pairs.isAutoClosedCloser(1))
    }

    @Test
    fun `forget and clear`() {
        val text = EditableText("(())")
        val pairs = AutoClosedPairs(text)
        pairs.record(0, 3)
        pairs.record(1, 2)
        pairs.forget(2)
        assertFalse(pairs.isAutoClosedCloser(2))
        assertTrue(pairs.isAutoClosedCloser(3))
        pairs.forget(99)
        pairs.clear()
        assertFalse(pairs.isAutoClosedCloser(3))
    }

    @Test
    fun `recording needs an opener before the closer inside the text`() {
        val pairs = AutoClosedPairs(EditableText("()"))
        assertFailsWith<IllegalArgumentException> { pairs.record(1, 1) }
        assertFailsWith<IllegalArgumentException> { pairs.record(0, 2) }
        assertFailsWith<IllegalArgumentException> { pairs.record(-1, 1) }
    }

    @Test
    fun `the tracker keeps a bounded number of pairs`() {
        val text = EditableText("(".repeat(100) + ")".repeat(100))
        val pairs = AutoClosedPairs(text)
        for (i in 0 until 100) pairs.record(99 - i, 100 + i)
        assertTrue(pairs.isAutoClosedCloser(199), "the newest pair")
        assertFalse(pairs.isAutoClosedCloser(100), "the oldest pairs are forgotten")
    }
}
