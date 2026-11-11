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

/** Copy, cut and paste through the clipboard port, and undo and redo that restore the carets. */
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
    fun `a line cut without selection is pasted as a whole line above the caret's line`() {
        val editor = TestEditor("aaa\nbb|bbbb\nccc")

        editor.run(EditorActionIds.CUT)
        assertEquals("aaa\ncc|c", editor.marked())
        assertTrue(editor.run(EditorActionIds.PASTE))

        assertEquals("aaa\nbbbbbb\ncc|c", editor.marked(), "the line moved back, the caret keeps its column")
    }

    @Test
    fun `a line copied without selection is pasted as a whole line above the caret's line`() {
        val editor = TestEditor("aaa\nbb|bbbb\nccc")

        editor.run(EditorActionIds.COPY)
        assertTrue(editor.run(EditorActionIds.PASTE))

        assertEquals("aaa\nbbbbbb\nbb|bbbb\nccc", editor.marked(), "the line is duplicated above")
        editor.run(EditorActionIds.UNDO)
        assertEquals("aaa\nbb|bbbb\nccc", editor.marked(), "one undo step")
    }

    @Test
    fun `a whole line replaces a selection like any text, and a text from elsewhere is pasted at the caret`() {
        val editor = TestEditor("aaa\nbb|b")
        editor.run(EditorActionIds.COPY)
        editor.caret.select(0, 1)
        editor.run(EditorActionIds.PASTE)
        assertEquals("bbb\n|aa\nbbb", editor.marked(), "the selection is replaced")

        editor.clipboard.content = "x"
        editor.caret.moveTo(2)
        editor.run(EditorActionIds.PASTE)
        assertEquals("bbx|b\naa\nbbb", editor.marked(), "another application's text carries no line mark")
    }

    @Test
    fun `cut keeps the text and copy reports nothing done when the clipboard cannot be written`() {
        val editor = TestEditor("ab^cd|ef")
        editor.clipboard.failing = true

        assertFalse(editor.run(EditorActionIds.CUT))
        assertEquals("ab^cd|ef", editor.marked(), "nothing was lost")
        assertFalse(editor.run(EditorActionIds.COPY))
        assertEquals(0L, editor.text.snapshot.value.version)
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
            override fun setText(text: String, wholeLine: Boolean): Boolean = true
            override fun holdsWholeLine(): Boolean = false.also { reads++ }
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
