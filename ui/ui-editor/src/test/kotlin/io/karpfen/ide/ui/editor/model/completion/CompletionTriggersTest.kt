package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.ui.editor.model.action.TestEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The auto-triggers, decided on the text after the typing action ran (spec §7.1). */
class CompletionTriggersTest {

    /** Types [typed] into [marked] like the editor (auto-close included) and asks for the trigger. */
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
}
