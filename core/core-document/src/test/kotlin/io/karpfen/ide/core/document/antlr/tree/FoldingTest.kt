package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kmodel.KmodelParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [BlockFolding] and [CommentFolding]: multi-line brace pairs, unmatched and skipped braces, runs of whole-line
 * comments and what breaks a run.
 */
class FoldingTest {

    private val kmodel = TreeParser(DslGrammar.KMODEL, ::KmodelParser, KmodelParser::kmodel_file)

    private fun blocks(text: String, skip: (Int) -> Boolean = { false }): List<TextRange> =
        BlockFolding.of(kmodel.parse(TextSnapshot(text, 0)), KmodelParser.LBRACE, KmodelParser.RBRACE, skip).map { it.range }

    private fun comments(text: String): List<FoldingRange> {
        val snapshot = TextSnapshot(text, 0)
        return CommentFolding.of(snapshot, GapComments.of(kmodel.parse(snapshot)))
    }

    @Test
    fun `brace pairs spanning lines fold from the opening through the closing brace`() {
        val text = "make object \"a\":\"A\" {\n has(\"b\") -> make object \"b\":\"B\" { }\n}"

        assertEquals(listOf(TextRange(text.indexOf('{'), text.length)), blocks(text))
    }

    @Test
    fun `unmatched braces fold nothing and do not disturb other pairs`() {
        assertEquals(emptyList(), blocks("make object \"a\":\"A\" {\n prop(\"x\") -> \"1\"\n"))
        val text = "}\nmake object \"a\":\"A\" {\n}\n}"
        assertEquals(listOf(TextRange(text.indexOf('{'), text.indexOf('{') + 3)), blocks(text))
    }

    @Test
    fun `skipped braces are ignored`() {
        val text = "make object \"a\":\"A\" {\n}"
        assertEquals(emptyList(), blocks(text) { index -> index == 5 })
    }

    @Test
    fun `runs of two or more whole-line comments fold`() {
        val text = "// a\n  // b\n// c\n\n// alone\nmake object \"a\":\"A\" { } // trailing\n// after code"

        assertEquals(listOf(FoldingRange(TextRange(0, text.indexOf("// c") + 4), FoldingKind.COMMENT)), comments(text))
    }

    @Test
    fun `a trailing comment breaks a run`() {
        val text = "// a\nmake object \"a\":\"A\" { // b\n// c\n// d\n}"

        assertEquals(listOf(TextRange(text.indexOf("// c"), text.indexOf("// d") + 4)), comments(text).map { it.range })
    }

    @Test
    fun `multi-line means a later line of the snapshot`() {
        val snapshot = TextSnapshot("a\nb", 0)
        assertEquals(true, BlockFolding.isMultiLine(snapshot, TextRange(0, 3)))
        assertEquals(false, BlockFolding.isMultiLine(snapshot, TextRange(0, 1)))
        assertEquals(false, BlockFolding.isMultiLine(TextSnapshot("a\rb", 0), TextRange(0, 3)), "only \\n separates lines")
    }
}
