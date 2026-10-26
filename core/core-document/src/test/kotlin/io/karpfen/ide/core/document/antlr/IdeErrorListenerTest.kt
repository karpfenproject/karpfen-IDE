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

import io.karpfen.dsl.grammar.kmeta.KmetaParser
import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token

/**
 * Covers [IdeErrorListener]: located LEXER and PARSER diagnostics instead of console output, error-free examples, and
 * lexer diagnostics of mutated texts that stay inside the text.
 */
class IdeErrorListenerTest {

    @Test
    fun `lexer errors become located LEXER diagnostics`() {
        val text = "// 😀\ntype \"A\" \"d\" { @ }"

        val lexed = KarpfenLexers.lex(DslGrammar.KMETA, text)

        val diagnostic = lexed.diagnostics.single()
        assertEquals(DiagnosticSource.LEXER, diagnostic.source)
        assertEquals(DiagnosticCodes.LEXER_ERROR, diagnostic.code)
        assertEquals(TextRange.ofLength(text.indexOf('@'), 1), diagnostic.range, "UTF-16 range after a surrogate pair")
        assertEquals(listOf("type", "\"A\"", "\"d\"", "{", "}"), lexed.tokens.map { it.text })
    }

    @Test
    fun `parser errors become PARSER diagnostics at the offending token`() {
        val missingDescription = "type \"A\" { }"
        assertEquals(
            listOf(TextRange.ofLength(missingDescription.indexOf('{'), 1)),
            parseKmeta(missingDescription).map { it.range },
        )

        val unclosed = "type \"A\" \"d\" {"
        val atEnd = parseKmeta(unclosed)
        assertTrue(atEnd.isNotEmpty())
        assertTrue(atEnd.all { it.source == DiagnosticSource.PARSER && it.code == DiagnosticCodes.SYNTAX_ERROR })
        assertEquals(TextRange.empty(unclosed.length), atEnd.last().range, "<EOF> is an empty range at the end")
    }

    /** The KStates lexer never reports errors; it keeps whitespace and comments on the hidden channel. */
    @Test
    fun `examples lex without errors and the KStates lexer never reports any`() {
        Examples.files().forEach { file ->
            assertEquals(emptyList(), KarpfenLexers.lex(grammarOf(file.language), file.text).diagnostics, "lexer errors in $file")
        }
        val machine = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text
        Mutations.random(machine, seed = 11, count = 500).forEach { mutation ->
            assertEquals(emptyList(), KarpfenLexers.lex(DslGrammar.KSTATES, mutation.text).diagnostics, "$mutation")
        }
        val hidden = KarpfenLexers.lex(DslGrammar.KSTATES, "STATE // c\n").tokens.filter { it.channel == Token.HIDDEN_CHANNEL }
        assertEquals(listOf(KstatesLexer.WS, KstatesLexer.LINE_COMMENT, KstatesLexer.WS), hidden.map { it.type })
    }

    @Test
    fun `lexer diagnostics of mutated KMeta and KModel stay inside the text`() {
        for (language in listOf(KarpfenLanguage.KMETA, KarpfenLanguage.KMODEL)) {
            val source = Examples.files(language).first().text
            Mutations.random(source, seed = 5, count = 500).forEach { mutation ->
                KarpfenLexers.lex(grammarOf(language), mutation.text).diagnostics.forEach { diagnostic ->
                    assertTrue(diagnostic.range.end <= mutation.text.length, "$diagnostic outside the text of $mutation")
                }
            }
        }
    }

    private fun parseKmeta(text: String): List<io.karpfen.ide.core.document.Diagnostic> {
        val offsets = CodePointOffsets.of(text)
        val listener = IdeErrorListener(offsets)
        val tokens = CommonTokenStream(KarpfenLexers.create(DslGrammar.KMETA, CharStreams.fromString(text), listener))
        listener.attachTo(KmetaParser(tokens)).kmeta_file()
        return listener.diagnostics.filter { it.source == DiagnosticSource.PARSER }
    }
}
