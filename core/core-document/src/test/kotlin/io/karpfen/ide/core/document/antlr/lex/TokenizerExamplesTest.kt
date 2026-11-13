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
package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.document.syntax.TokenKind
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the tokenizers on the examples: every example tokenizes without LEXER diagnostics and with valid tokens. */
class TokenizerExamplesTest {

    @Test
    fun `every example tokenizes with zero lexer diagnostics and valid tokens`(reporter: TestReporter) {
        for (file in Examples.files()) {
            val result = tokenizeChecked(file.language, file.text, file.path)

            assertTrue(result.diagnostics.none { it.source == DiagnosticSource.LEXER }, "$file: ${result.diagnostics}")
            assertTrue(result.tokens.none { it.kind == TokenKind.ERROR }, "$file has error tokens")
            reporter.publishEntry(file.path, "${result.tokens.size} tokens, diagnostics ${result.diagnostics.map { it.code }}")
        }
    }

    @Test
    fun `the examples tokenize without diagnostics`() {
        for (file in Examples.files()) {
            val result = tokenizeChecked(file.language, file.text, file.path)

            assertTrue(result.diagnostics.isEmpty(), "$file: ${result.diagnostics}")
        }
    }

    @Test
    fun `KStates examples have embedded regions with embedded tokens and karpfen comments outside them`() {
        for (file in Examples.files(KarpfenLanguage.KSTATES)) {
            val result = tokenizeChecked(file.language, file.text, file.path)

            assertTrue(result.embeddedRegions.isNotEmpty(), "$file has no EVAL bodies")
            assertTrue(result.tokens.any { it.kind == TokenKind.PATH_REFERENCE }, "$file has no path references")
            assertTrue(result.tokens.any { it.kind == TokenKind.OPERATION }, "$file has no operations")
            val comments = result.tokens.filter { it.kind == TokenKind.COMMENT && result.embeddedRegionAt(it.range.start) == null }
            assertTrue(comments.isNotEmpty(), "$file has no karpfen comments")
        }
    }

    @Test
    fun `the synthetic document tokenizes cleanly`() {
        val text = SyntheticKstates.withMinLines(1_000)

        val result = tokenizeChecked(KarpfenLanguage.KSTATES, text, "synthetic")

        assertTrue(result.diagnostics.isEmpty(), "${result.diagnostics.take(5)}")
        assertEquals(text.split("EVAL {").size - 1, result.embeddedRegions.size)
    }
}
