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

/**
 * Covers [LexerGaps]: recovering comments and unrecognized input from the gaps the KMeta and KModel lexers leave,
 * so that tokens and gap pieces cover every non-whitespace character exactly once.
 */
class LexerGapsTest {

    private fun gaps(language: KarpfenLanguage, text: String): List<Pair<GapKind, String>> {
        val lexed = KarpfenLexers.lex(grammarOf(language), text)
        return LexerGaps.scan(text, lexed.tokens.mapNotNull(lexed::rangeOf))
            .map { it.kind to text.substring(it.range.start, it.range.end) }
    }

    /** As in the lexer, a comment runs to the end of its line and swallows a closing brace on it. */
    @Test
    fun `comments and unrecognized input are recovered from the gaps`() {
        assertEquals(
            listOf(GapKind.COMMENT to "// head", GapKind.UNRECOGNIZED to "@", GapKind.COMMENT to "// tail"),
            gaps(KarpfenLanguage.KMETA, "// head\ntype \"A\" \"d\" { @ } // tail"),
        )
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

    /**
     * Asserts that tokens and gap pieces of [text] do not overlap, cover every non-whitespace character, and that the
     * lexer reported everything it skipped without a comment rule.
     */
    private fun assertCoverage(language: KarpfenLanguage, text: String, label: String) {
        val lexed = KarpfenLexers.lex(grammarOf(language), text)
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
        val lexerErrors = lexed.diagnostics.filter { it.source == DiagnosticSource.LEXER }.map { it.range }
        pieces.filter { it.kind == GapKind.UNRECOGNIZED }.forEach { piece ->
            for (k in piece.range.start until piece.range.end) {
                assertTrue(lexerErrors.any { it.containsChar(k) }, "unreported unrecognized input at $k in $label")
            }
        }
    }

    private fun TextRange.containsChar(offset: Int): Boolean = offset in this
}
