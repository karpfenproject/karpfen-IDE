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

/** Spec §11 item 2 for the tokenizers: every example tokenizes without LEXER diagnostics and with valid tokens. */
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
    fun `the only diagnostics of the examples are eval-double-slash warnings`() {
        for (file in Examples.files()) {
            val result = tokenizeChecked(file.language, file.text, file.path)

            assertTrue(result.diagnostics.all { it.code == DiagnosticCodes.EVAL_DOUBLE_SLASH }, "$file: ${result.diagnostics}")
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
