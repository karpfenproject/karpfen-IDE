package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.caret
import io.karpfen.ide.core.text.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The token-based caret context (spec §5): strings, calls, blocks, statements, embedded Python and comments. */
class CaretContextDetectorTest {

    private val detector = CaretContextDetector()

    private fun detect(name: String, textWithCaret: String): Pair<String, CaretContext> {
        val (text, offset) = caret(textWithCaret)
        return text to detector.detect(ExampleProject.document(Path.of("/ws/$name"), text), offset)
    }

    @Test
    fun `a caret inside a closed string has its content range and the replace range is the whole content`() {
        val (text, context) = detect("a.kmeta", "type \"Vec‸tor\" \"d\" { }")

        val string = assertNotNull(context.string)
        assertTrue(string.closed)
        assertEquals("Vector", text.substring(string.contentRange.start, string.contentRange.end))
        assertEquals(string.contentRange, context.replaceRange)
        assertEquals(TextRange(6, 12), context.wordRange)
    }

    @Test
    fun `carets on the quotes are outside the string, right after the opening quote inside`() {
        val text = "type \"V\" \"d\" { }"
        val document = ExampleProject.document(Path.of("/ws/a.kmeta"), text)

        assertNull(detector.detect(document, 5).string, "before the opening quote")
        assertNotNull(detector.detect(document, 6).string, "after the opening quote")
        assertNotNull(detector.detect(document, 7).string, "before the closing quote")
        assertNull(detector.detect(document, 8).string, "after the closing quote")
    }

    @Test
    fun `an unterminated KStates string runs to the end of the line and replaces up to the word end`() {
        val (text, context) = detect("a.kstates", "STATEMACHINE ATTACHED TO \"Ro‸b\n")

        val string = assertNotNull(context.string)
        assertFalse(string.closed)
        assertEquals("Rob", text.substring(string.contentRange.start, string.contentRange.end))
        assertEquals("Rob", text.substring(context.replaceRange.start, context.replaceRange.end))
        assertEquals(listOf("STATEMACHINE", "ATTACHED", "TO"), context.statement.map { it.text })
    }

    @Test
    fun `an unterminated KMeta string is found from the ERROR token the lexer leaves`() {
        val (text, context) = detect("a.kmeta", "type \"A\" \"d\" {\n    has(\"x\", \"Vec‸\n}")

        val string = assertNotNull(context.string)
        assertFalse(string.closed)
        assertEquals("Vec", text.substring(context.replaceRange.start, context.replaceRange.end))
        val call = assertNotNull(context.call)
        assertEquals("has", call.name)
        assertEquals(1, call.argumentIndex)
        assertEquals(listOf("x"), call.arguments)
    }

    @Test
    fun `the innermost call has its argument index, string arguments and parent call`() {
        val (_, context) = detect("a.kmeta", "type \"A\" \"d\" { prop(\"log\", list(\"‸\")) }")

        val call = assertNotNull(context.call)
        assertEquals("list", call.name)
        assertEquals(0, call.argumentIndex)
        val parent = assertNotNull(call.parent)
        assertEquals("prop", parent.name)
        assertEquals(1, parent.argumentIndex)
        assertEquals(listOf("log"), parent.arguments)
    }

    @Test
    fun `a call is found across an EVAL argument and not across an unclosed block`() {
        val (_, afterEval) = detect("a.kstates", "SETOBJ(\"self\", \"r\", EVAL { return {1: 2} }, \"‸\")")
        val call = assertNotNull(afterEval.call)
        assertEquals("SETOBJ", call.name)
        assertEquals(3, call.argumentIndex)
        assertEquals(listOf("self", "r", null), call.arguments)

        val (_, inBlock) = detect("a.kstates", "SET(\"a\", MACRO(\"m\") STATE \"s\" { ‸")
        assertNull(inBlock.call)
    }

    @Test
    fun `blocks are listed outermost first with headers and ranges, unclosed ones to the end of the text`() {
        val (text, context) = detect(
            "a.kstates",
            "STATEMACHINE ATTACHED TO \"Robot\" {\n STATES {\n  STATE \"a\" {\n   ENTRY { SET(\"x\", \"1\") WITH MACRO(\"m\", \"p\") AS \"d\" { ‸ }\n  }\n }\n",
        )

        assertEquals(
            listOf("STATEMACHINE ATTACHED TO Robot", "STATES", "STATE a", "ENTRY", "SET ( x , 1 ) WITH MACRO ( m , p ) AS d"),
            context.blocks.map { frame -> frame.header.joinToString(" ") { it.text } },
        )
        assertEquals(text.length, context.blocks.first().range.end, "the machine is not closed")
        val with = context.blocks.last()
        assertEquals("{ ‸ }".length - 1, with.range.length)
        assertEquals(TokenKind.STRING, with.header.last().kind)
    }

    @Test
    fun `an EVAL argument's block header starts after the call's last comma`() {
        val (_, context) = detect("a.kstates", "DO { SET(\"x\", EVAL { return ‸ }) }")

        assertEquals(listOf("EVAL"), context.blocks.last().header.map { it.text })
    }

    @Test
    fun `embedded contexts come from the tokenizer's EVAL regions, with path and macro prefixes`() {
        val path = detect("a.kstates", "SET(\"x\", EVAL { return \$( boundingBox -> pos‸ ) })")
        val embedded = assertNotNull(path.second.embedded)
        assertEquals("boundingBox->pos", embedded.pathPrefix)
        assertNull(embedded.macroPrefix)
        assertNull(path.second.string)
        assertNull(path.second.call)

        val macro = detect("a.kstates", "SET(\"x\", EVAL { return @poi‸nt(1) })").second
        assertEquals("poi", assertNotNull(macro.embedded).macroPrefix)

        val lone = detect("a.kstates", "SET(\"x\", EVAL { return @‸ })").second
        assertEquals("", assertNotNull(lone.embedded).macroPrefix)

        val plain = detect("a.kstates", "SET(\"x\", EVAL { ret‸urn 1 })").second
        val code = assertNotNull(plain.embedded)
        assertNull(code.pathPrefix)
        assertNull(code.macroPrefix)
    }

    @Test
    fun `the embedded region has inclusive bounds and agrees with the tokens`() {
        val (text, offset) = caret("SET(\"x\", EVAL {‸ return 1 })")
        val document = ExampleProject.document(Path.of("/ws/a.kstates"), text)

        val atOpen = assertNotNull(detector.detect(document, offset).embedded)
        assertEquals(document.tokens.embeddedRegionAt(offset), atOpen.bodyRange)
        val close = text.lastIndexOf('}')
        assertNotNull(detector.detect(document, close).embedded, "right before the closing brace")
        assertNull(detector.detect(document, close + 1).embedded, "after the closing brace")
    }

    @Test
    fun `comments are recognized and their strings are not strings`() {
        val (_, context) = detect("a.kstates", "STATES { // see \"a‸\" }")

        assertTrue(context.inComment)
        assertNull(context.string)
    }

    @Test
    fun `the word range covers identifier characters around the caret`() {
        val (text, context) = detect("a.kstates", "STATES { INIT‸IAL }")

        assertEquals("INITIAL", text.substring(context.wordRange.start, context.wordRange.end))
        assertEquals(context.wordRange, context.replaceRange)
        assertEquals(listOf("STATES"), context.blocks.single().header.map { it.text })
        assertTrue(context.statement.isEmpty(), "the word at the caret is not part of the statement")
    }

    @Test
    fun `offsets outside the text are clamped and an empty text works`() {
        val document = ExampleProject.document(Path.of("/ws/a.kmodel"), "")

        assertEquals(0, detector.detect(document, 5).offset)
        assertEquals(CaretContext(0, TextRange.EMPTY, null, null, emptyList(), null), detector.detect(document, -3))
    }
}
