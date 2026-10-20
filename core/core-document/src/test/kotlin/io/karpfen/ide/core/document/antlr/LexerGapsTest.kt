package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.LexerGaps.GapKind
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexerGapsTest {

    private fun gaps(language: KarpfenLanguage, text: String): List<Pair<GapKind, String>> {
        val lexed = KarpfenLexers.lex(language, text)
        return LexerGaps.scan(text, lexed.tokens.mapNotNull(lexed::rangeOf))
            .map { it.kind to text.substring(it.range.start, it.range.end) }
    }

    @Test
    fun `comments and unrecognized input are recovered from the gaps`() {
        assertEquals(
            listOf(GapKind.COMMENT to "// head", GapKind.UNRECOGNIZED to "@", GapKind.COMMENT to "// tail"),
            gaps(KarpfenLanguage.KMETA, "// head\ntype \"A\" \"d\" { @ } // tail"),
        )
        // As in the lexer, a comment runs to the end of the line and swallows the closing brace.
        assertEquals(
            listOf(GapKind.UNRECOGNIZED to "/x", GapKind.COMMENT to "//y }"),
            gaps(KarpfenLanguage.KMETA, "type \"A\" \"d\" { /x //y }"),
        )
        assertEquals(
            listOf(GapKind.COMMENT to "// c"),
            gaps(KarpfenLanguage.KMODEL, "make object \"a\":\"T\" { // c\r\n}"),
        )
    }

    @Test
    fun `tokens and gap pieces cover every non-whitespace character exactly once`() {
        for (file in Examples.files().filter { it.language != KarpfenLanguage.KSTATES }) {
            val texts = listOf(file.text) + Mutations.random(file.text, seed = 3, count = 150).map { it.text }
            texts.forEach { text -> assertCoverage(file.language, text, "$file") }
        }
    }

    private fun assertCoverage(language: KarpfenLanguage, text: String, label: String) {
        val lexed = KarpfenLexers.lex(language, text)
        val tokens = lexed.tokens.mapNotNull(lexed::rangeOf)
        val pieces = LexerGaps.scan(text, tokens)
        val all = (tokens + pieces.map { it.range }).sortedBy { it.start }
        for (i in 1 until all.size) {
            assertTrue(all[i - 1].end <= all[i].start, "overlap ${all[i - 1]} / ${all[i]} in $label: ${text.take(80)}")
        }
        val covered = BooleanArray(text.length)
        all.forEach { range -> for (k in range.start until range.end) covered[k] = true }
        text.forEachIndexed { k, char ->
            assertTrue(covered[k] || char in " \t\r\n", "uncovered '${char}' at $k in $label")
        }
        // Everything the lexer skipped without a comment rule was reported by it.
        val lexerErrors = lexed.diagnostics.filter { it.source == DiagnosticSource.LEXER }.map { it.range }
        pieces.filter { it.kind == GapKind.UNRECOGNIZED }.forEach { piece ->
            for (k in piece.range.start until piece.range.end) {
                assertTrue(lexerErrors.any { it.containsChar(k) }, "unreported unrecognized input at $k in $label")
            }
        }
    }

    private fun TextRange.containsChar(offset: Int): Boolean = offset in this
}
