package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Typing, auto-close, over-type and Enter with auto-indent, for braces and for the square brackets of lists. */
class TypingActionsTest {

    private fun typed(marked: String, typed: String): String = TestEditor(marked).apply { type(typed) }.marked()

    private fun enter(marked: String): String = TestEditor(marked).apply { run(EditorActionIds.ENTER) }.marked()

    @Test
    fun `typing inserts at the caret and replaces a selection`() {
        assertEquals("abx|c", typed("ab|c", "x"))
        assertEquals("ax|d", typed("a^bc|d", "x"))
        assertEquals("ax|d", typed("a|bc^d", "x"))
        assertEquals("hello wor|ld", typed("hello |ld", "wor"))
        assertFalse(TestEditor("a|").type(""), "nothing typed")
    }

    @Test
    fun `typed line separators are normalized and a typed newline is Enter`() {
        assertEquals("a\nb|", typed("a|", "\r\nb"))
        assertEquals("x {\n    |\n}", typed("x {|}", "\n"))
        assertEquals("x {\n    |\n}", typed("x {|}", "\r"))
    }

    @Test
    fun `typing an opener auto-closes before whitespace, line end and closing brackets`() {
        assertEquals("f(|)", typed("f|", "("))
        assertEquals("f(|) x", typed("f| x", "("))
        assertEquals("f(|)\nx", typed("f|\nx", "("))
        assertEquals("g((|))", typed("g(|)", "("))
        assertEquals("a{|}]", typed("a|]", "{"))
        assertEquals("\"|\"", typed("|", "\""))
        assertEquals("x = \"|\" ", typed("x = | ", "\""))
    }

    @Test
    fun `an opening square bracket auto-closes, is over-typed and is deleted with its closer like the other brackets`() {
        assertEquals("\"stateMachines\": [|]", typed("\"stateMachines\": |", "["), "a JSON list of a descriptor")
        assertEquals("x = np.array([|])", typed("x = np.array(|)", "["), "a Python list in an EVAL body")
        assertEquals("[|a", typed("|a", "["))
        val editor = TestEditor("\"domains\": |")
        editor.typeEach("[\"public\"]")
        assertEquals("\"domains\": [\"public\"]|", editor.marked(), "the closing quote and bracket were over-typed")
        val emptied = TestEditor("\"bridges\": |").apply {
            type("[")
            run(EditorActionIds.BACKSPACE)
        }
        assertEquals("\"bridges\": |", emptied.marked(), "Backspace removes the auto-inserted pair")
    }

    @Test
    fun `typing an opener before other text does not auto-close`() {
        assertEquals("f(|x", typed("f|x", "("))
        assertEquals("{|a}", typed("|a}", "{"))
        assertEquals("\"|abc", typed("|abc", "\""))
    }

    @Test
    fun `a quote that closes a string is not auto-closed`() {
        assertEquals("\"abc\"|", typed("\"abc|", "\""))
        assertEquals("x(\"a\", \"|\")", typed("x(\"a\", |)", "\""), "an even number of quotes before: a new string")
        assertEquals("\"a\\\"b\"|", typed("\"a\\\"b|", "\""), "an escaped quote does not count")
    }

    @Test
    fun `typing with a selection replaces it without auto-closing`() {
        assertEquals("(|", typed("^ab|", "("))
    }

    @Test
    fun `typing an auto-inserted closer moves over it`() {
        val editor = TestEditor("f|")
        editor.type("(")
        editor.type("x")
        editor.type(")")
        assertEquals("f(x)|", editor.marked())
        editor.type(")")
        assertEquals("f(x))|", editor.marked(), "the closer was over-typed once, a second ) is inserted")
    }

    @Test
    fun `nested auto-closed pairs are over-typed from the inside out`() {
        val editor = TestEditor("|")
        editor.typeEach("({\"")
        assertEquals("({\"|\"})", editor.marked())
        editor.typeEach("a\"})")
        assertEquals("({\"a\"})|", editor.marked())
    }

    @Test
    fun `a typed closer before a user-typed closer is inserted, not over-typed`() {
        val editor = TestEditor("f|x")
        editor.type("(")
        editor.type(")")
        assertEquals("f()|x", editor.marked(), "neither closer was auto-inserted")
        editor.caret.moveTo(2)
        editor.type(")")
        assertEquals("f()|)x", editor.marked(), "a user-typed ) is never over-typed")
    }

    @Test
    fun `a closer that the user deleted and retyped is not tracked any more`() {
        val editor = TestEditor("f|")
        editor.type("(")
        editor.run(EditorActionIds.DELETE)
        editor.type(")")
        editor.caret.moveTo(2)
        editor.type(")")
        assertEquals("f()|)", editor.marked())
    }

    @Test
    fun `typing coalesces into one undo step and auto-close is a step of its own`() {
        val editor = TestEditor("|")
        editor.typeEach("ab")
        editor.type("(")
        editor.typeEach("cd")
        assertEquals("ab(cd|)", editor.marked())
        editor.run(EditorActionIds.UNDO)
        assertEquals("ab(|)", editor.marked())
        editor.run(EditorActionIds.UNDO)
        assertEquals("ab|", editor.marked())
        editor.run(EditorActionIds.UNDO)
        assertEquals("|", editor.marked())
    }

    @Test
    fun `typing records caret mementos for undo and redo`() {
        val editor = TestEditor("a^bc|d")
        editor.type("x")
        editor.caret.moveTo(0)
        editor.run(EditorActionIds.UNDO)
        assertEquals("a^bc|d", editor.marked())
        editor.caret.moveTo(0)
        editor.run(EditorActionIds.REDO)
        assertEquals("ax|d", editor.marked())
    }

    @Test
    fun `enter keeps the indentation of the line`() {
        assertEquals("    foo\n    |", enter("    foo|"))
        assertEquals("\tfoo\n\t|bar", enter("\tfoo|bar"))
        assertEquals("foo\n|", enter("foo|"))
        assertEquals("  \n  |  foo", enter("  |  foo"), "the indentation up to the caret")
    }

    @Test
    fun `enter after an opening brace indents one level more`() {
        assertEquals("STATE \"a\" {\n    |", enter("STATE \"a\" {|"))
        assertEquals("    x {  \n        |", enter("    x {  |"))
        assertEquals("    x {\n        |y", enter("    x {|y"))
    }

    @Test
    fun `enter between braces puts the closing brace on its own line`() {
        assertEquals("x {\n    |\n}", enter("x {|}"))
        assertEquals("    x {\n        |\n    }", enter("    x {|}"))
        assertEquals("x { \n    |\n} y", enter("x { |  } y"), "whitespace before } is dropped")
        val autoClosed = TestEditor("x |").apply {
            type("{")
            run(EditorActionIds.ENTER)
        }
        assertEquals("x {\n    |\n}", autoClosed.marked(), "Enter right after an auto-closed {")
    }

    @Test
    fun `enter after an opening square bracket indents one level more`() {
        assertEquals("    \"a\": [\n        |", enter("    \"a\": [|"))
        assertEquals("x = [ \n    |1", enter("x = [ |1"))
    }

    @Test
    fun `enter between square brackets puts the closing bracket on its own line`() {
        assertEquals("    \"stateMachines\": [\n        |\n    ]", enter("    \"stateMachines\": [|]"))
        assertEquals("x = f([\n    |\n])", enter("x = f([|])"))
        val autoClosed = TestEditor("    \"bridges\": |").apply {
            type("[")
            run(EditorActionIds.ENTER)
        }
        assertEquals("    \"bridges\": [\n        |\n    ]", autoClosed.marked(), "Enter right after an auto-closed [")
    }

    @Test
    fun `enter leaves a closer of the other kind where it is`() {
        assertEquals("x {\n    |]", enter("x {|]"))
        assertEquals("x [\n    |}", enter("x [|}"))
    }

    @Test
    fun `enter replaces a selection`() {
        assertEquals("  a\n  |d", enter("  a^bc|d"))
    }

    @Test
    fun `enter is one undo step that restores the caret`() {
        val editor = TestEditor("x {|}")
        editor.run(EditorActionIds.ENTER)
        editor.run(EditorActionIds.UNDO)
        assertEquals("x {|}", editor.marked())
        editor.run(EditorActionIds.REDO)
        assertEquals("x {\n    |\n}", editor.marked())
    }

    @Test
    fun `typing after an external edit uses caught-up carets`() {
        val editor = TestEditor("abc|")
        editor.text.apply(listOf(TextEdit.insert(0, "123")), EditOrigin.External)
        assertTrue(editor.type("!"))
        assertEquals("123abc!|", editor.marked())
        assertEquals(listOf(CaretMemento.at(7)), editor.caret.mementos())
    }
}
