package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.dsl.grammar.kmeta.KmetaParser
import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.dsl.grammar.kstates.KstatesParser
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TreeParser] and the range arithmetic of [ParsedTree] on complete, broken and non-BMP input. */
class TreeParserTest {

    private val kmeta = TreeParser(DslGrammar.KMETA, ::KmetaParser, KmetaParser::kmeta_file)
    private val kstates = TreeParser(DslGrammar.KSTATES, ::KstatesParser, KstatesParser::kstates_file)

    @Test
    fun `lexer and parser errors are collected, nothing is printed`() {
        val text = "type \"A\" \"d\" { @ prop(\"x\" \"number\") }"

        val tree = kmeta.parse(TextSnapshot(text, 0))

        assertEquals(setOf(DiagnosticSource.LEXER, DiagnosticSource.PARSER), tree.diagnostics.map { it.source }.toSet())
        assertEquals(TextRange.ofLength(text.indexOf('@'), 1), tree.diagnostics.first { it.source == DiagnosticSource.LEXER }.range)
    }

    @Test
    fun `the stream keeps every token of every channel and ends with EOF`() {
        val text = "STATEMACHINE // c\n ATTACHED"

        val tree = kstates.parse(TextSnapshot(text, 0))

        assertEquals(Token.EOF, tree.tokens.last().type)
        assertTrue(tree.tokens.any { it.type == KstatesLexer.LINE_COMMENT })
        assertEquals(TextRange.empty(text.length), tree.rangeOf(tree.tokens.last()))
    }

    @Test
    fun `ranges are UTF-16 offsets after supplementary characters`() {
        val text = "// 😀\ntype \"V\" \"😀 d\" { }"

        val tree = kmeta.parse(TextSnapshot(text, 0))
        val type = tree.root.type_definition().single()
        val strings = tree.quotedStrings(type, KmetaParser.STRING)

        assertEquals(TextRange(text.indexOf("type"), text.length), tree.rangeOf(type))
        assertEquals(listOf("V", "😀 d"), strings.map { it!!.value })
        assertEquals(TextRange.ofLength(text.indexOf("\"V\"") + 1, 1), strings[0]!!.inner)
    }

    @Test
    fun `conjured tokens keep their position but have no range, skipped tokens are dropped`() {
        val missing = "type \"A\" { }"
        val conjured = kmeta.parse(TextSnapshot(missing, 0))
        val type = conjured.root.type_definition().single()

        val strings = conjured.terminals(type, KmetaParser.STRING)
        assertEquals(2, strings.size, "the missing description is conjured at its position")
        assertNull(conjured.rangeOf(strings[1]))
        assertNull(conjured.quoted(strings[1]))
        assertEquals("A", conjured.quoted(strings[0])!!.value)

        val extra = "type \"A\" \"d\" \"junk\" { }"
        val recovered = kmeta.parse(TextSnapshot(extra, 0))
        val withJunk = recovered.root.type_definition().single()
        assertEquals(3, withJunk.STRING().size, "ANTLR keeps the skipped string as an error node")
        assertEquals(listOf("A", "d"), recovered.quotedStrings(withJunk, KmetaParser.STRING).map { it!!.value })
    }

    @Test
    fun `a rule that consumed nothing has an empty range at its start`() {
        val text = "STATEMACHINE"
        val tree = kstates.parse(TextSnapshot(text, 0))
        val machine = tree.root.statemachine()

        assertEquals(TextRange(0, text.length), tree.rangeOf(machine))
        val states = machine.states_block()
        if (states != null) assertTrue(tree.rangeOf(states).isEmpty)
        assertEquals(TextRange.empty(3), tree.selectionOf(null, kmeta.parse(TextSnapshot("   type", 0)).root.type_definition().single()))
    }

    @Test
    fun `quoted strings drop one quote on each side`() {
        val text = "x \"\" 'ab'"
        assertEquals(QuotedString("", TextRange.empty(3), TextRange(2, 4)), QuotedString.of(text, TextRange(2, 4)))
        assertEquals(QuotedString("ab", TextRange(6, 8), TextRange(5, 9)), QuotedString.of(text, TextRange(5, 9)))
        assertEquals(QuotedString("", TextRange.empty(0), TextRange(0, 1)), QuotedString.of(text, TextRange(0, 1)))
    }
}
