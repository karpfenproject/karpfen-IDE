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

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.TestEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The auto-triggers, decided on the text after the typing action ran. */
class CompletionTriggersTest {

    /** Types [typed] into [marked] like the editor does, auto-close included, and asks for the trigger. */
    private fun trigger(marked: String, typed: String): CompletionTrigger? {
        val editor = TestEditor(marked)
        editor.type(typed)
        return CompletionTriggers.afterTyping(editor.text.snapshot.value, editor.caret.primary.position, typed)
    }

    @Test
    fun `an identifier character after an identifier character`() {
        assertEquals(CompletionTrigger.IDENTIFIER, trigger("p|", "r"))
        assertEquals(CompletionTrigger.IDENTIFIER, trigger("x_|", "1"))
        assertNull(trigger("|", "p"), "the first character of a word")
        assertNull(trigger("a |", "p"))
        assertNull(trigger("a.|", "p"))
        assertNull(trigger("ab|", "cd"), "more than one character at once (a paste through text input)")
        assertNull(trigger("ab|", " "))
    }

    @Test
    fun `a quote that opens a string`() {
        assertEquals(CompletionTrigger.QUOTE, trigger("TRANSITION |", "\""), "auto-closed")
        assertEquals(CompletionTrigger.QUOTE, trigger("prop(|x", "\""), "not auto-closed")
        assertNull(trigger("prop(\"x|", "\""), "a quote that closes a string")
        val overTyped = TestEditor("x = |")
        overTyped.typeEach("\"a\"")
        assertEquals("x = \"a\"|", overTyped.marked())
        val snapshot = overTyped.text.snapshot.value
        assertNull(CompletionTriggers.afterTyping(snapshot, overTyped.caret.primary.position, "\""), "an over-typed closing quote")
    }

    @Test
    fun `path start and arrow`() {
        assertEquals(CompletionTrigger.PATH_START, trigger("EVAL { x = $|", "("))
        assertEquals(CompletionTrigger.ARROW, trigger("SET(\"robot-|", ">"))
        assertNull(trigger("x |", "("))
        assertNull(trigger("x |", ">"))
        assertNull(trigger("x - |", ">"))
    }

    @Test
    fun `text that does not end at the caret triggers nothing`() {
        val editor = TestEditor("ab|")
        assertNull(CompletionTriggers.afterTyping(editor.text.snapshot.value, 2, "x"))
        assertNull(CompletionTriggers.afterTyping(editor.text.snapshot.value, 0, "a"))
        assertNull(CompletionTriggers.afterTyping(editor.text.snapshot.value, 2, ""))
    }

    /** Whether a request at the caret of [marked] still holds after [change] ran on the editor. */
    private fun holdsAfter(marked: String, change: TestEditor.() -> Unit): Boolean {
        val editor = TestEditor(marked)
        val snapshot = editor.text.snapshot.value
        val offset = editor.caret.primary.position
        editor.change()
        return CompletionTriggers.stillHolds(editor.text, snapshot.version, offset, editor.caret.primary)
    }

    @Test
    fun `a request still holds while the caret stays in the word it completes`() {
        assertEquals(true, holdsAfter("ST|") { type("A") }, "typed on")
        assertEquals(true, holdsAfter("ST|") { run(EditorActionIds.BACKSPACE) }, "deleted back")
        assertEquals(true, holdsAfter("EVAL { $(|") { type("x") }, "the first character after a path start")
        assertEquals(true, holdsAfter("ST|") { text.apply(listOf(TextEdit.insert(0, "x\n")), EditOrigin.External) }, "an edit elsewhere")
    }

    @Test
    fun `a request no longer holds after the word ended or the caret left it`() {
        assertEquals(false, holdsAfter("ST|") { run(EditorActionIds.ENTER) }, "a line break")
        assertEquals(false, holdsAfter("ST|") { type(" ") }, "a space")
        assertEquals(false, holdsAfter("ST|") { type(".") }, "punctuation")
        assertEquals(false, holdsAfter("ST| x") { caret.moveTo(4) }, "the caret moved out of the word")
        assertEquals(false, holdsAfter("ST|") { caret.select(0, 2) }, "a selection")
    }
}
