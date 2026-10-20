package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Copy, cut, paste through the clipboard port and undo/redo restoring carets (spec §7.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardAndHistoryActionsTest {

    @Test
    fun `copy puts the selection or the whole line on the clipboard`() {
        val editor = TestEditor("ab^cd|ef")
        assertTrue(editor.run(EditorActionIds.COPY))
        assertEquals("cd", editor.clipboard.content)
        assertEquals("ab^cd|ef", editor.marked(), "copy changes nothing")

        val line = TestEditor("one\ntw|o\nthree")
        line.run(EditorActionIds.COPY)
        assertEquals("two\n", line.clipboard.content)
        assertEquals(0L, line.text.snapshot.value.version)
    }

    @Test
    fun `cut removes the selection or the whole line`() {
        val editor = TestEditor("ab^cd|ef")
        assertTrue(editor.run(EditorActionIds.CUT))
        assertEquals("cd", editor.clipboard.content)
        assertEquals("ab|ef", editor.marked())

        val line = TestEditor("one\ntw|o\nthree")
        line.run(EditorActionIds.CUT)
        assertEquals("two\n", line.clipboard.content)
        assertEquals("one\nth|ree", line.marked())

        val last = TestEditor("one\ntw|o")
        last.run(EditorActionIds.CUT)
        assertEquals("two\n", last.clipboard.content)
        assertEquals("on|e", last.marked())
    }

    @Test
    fun `copy and cut are disabled only for an empty text without selection`() {
        val empty = TestEditor("|")
        assertFalse(empty.run(EditorActionIds.COPY))
        assertFalse(empty.run(EditorActionIds.CUT))
        assertNull(empty.clipboard.content)
        val emptyLine = TestEditor("a\n|")
        assertTrue(emptyLine.run(EditorActionIds.CUT))
        assertEquals("\n", emptyLine.clipboard.content)
        assertEquals("|a", emptyLine.marked(), "the caret keeps its column on the line above")
    }

    @Test
    fun `paste replaces the selection with normalized text`() {
        val editor = TestEditor("ab^cd|ef")
        editor.clipboard.content = "x\r\ny\rz"
        assertTrue(editor.run(EditorActionIds.PASTE))
        assertEquals("abx\ny\nz|ef", editor.marked())
    }

    @Test
    fun `paste is enabled with an empty clipboard and does nothing`() {
        val editor = TestEditor("ab|")
        val paste = editor.registry.get(EditorActionIds.PASTE)!!
        assertTrue(paste.isEnabled(editor))
        assertFalse(paste.execute(editor))
        editor.clipboard.content = ""
        assertTrue(paste.isEnabled(editor))
        assertFalse(paste.execute(editor))
        assertEquals(0L, editor.text.snapshot.value.version)
    }

    @Test
    fun `enablement never reads the clipboard`() {
        val editor = TestEditor("ab|")
        var reads = 0
        val counting = object : ClipboardPort {
            override fun getText(): String? = "x".also { reads++ }
            override fun setText(text: String) = Unit
        }
        val context = object : EditorActionContext by editor {
            override val clipboard: ClipboardPort = counting
        }
        defaultEditorActions().forEach { it.isEnabled(context) }
        assertEquals(0, reads)
        assertEquals(0, editor.tokenReads)
    }

    @Test
    fun `cut and paste are one undo step each with their own origins`() {
        val editor = TestEditor("ab^cd|ef")
        val origins = mutableListOf<EditOrigin>()
        CoroutineScope(UnconfinedTestDispatcher()).launch { editor.text.changes.collect { origins += it.origin } }
        editor.run(EditorActionIds.CUT)
        editor.run(EditorActionIds.PASTE)
        editor.run(EditorActionIds.PASTE)
        assertEquals("abcdcd|ef", editor.marked())
        assertEquals(listOf(EditOrigin.Cut, EditOrigin.Paste, EditOrigin.Paste), origins)
        editor.run(EditorActionIds.UNDO)
        assertEquals("abcd|ef", editor.marked())
        editor.run(EditorActionIds.UNDO)
        assertEquals("ab|ef", editor.marked())
        editor.run(EditorActionIds.UNDO)
        assertEquals("ab^cd|ef", editor.marked())
    }

    @Test
    fun `undo and redo are enabled by the history and restore the carets`() {
        val editor = TestEditor("a|")
        assertFalse(editor.run(EditorActionIds.UNDO), "nothing to undo")
        assertFalse(editor.run(EditorActionIds.REDO), "nothing to redo")
        editor.type("b")
        editor.caret.moveTo(0)
        assertTrue(editor.run(EditorActionIds.UNDO))
        assertEquals("a|", editor.marked())
        assertFalse(editor.registry.get(EditorActionIds.UNDO)!!.isEnabled(editor))
        assertTrue(editor.run(EditorActionIds.REDO))
        assertEquals("ab|", editor.marked())
    }

    @Test
    fun `undo of a change without carets leaves the carets to the caret model`() {
        val editor = TestEditor("abc|")
        editor.text.apply(listOf(TextEdit.insert(0, "xy")), EditOrigin.External)
        assertEquals("xyabc|", editor.marked())
        editor.run(EditorActionIds.UNDO)
        assertEquals("abc|", editor.marked(), "mapped back through the undo change")
    }
}
