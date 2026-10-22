package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.dsl.grammar.kmeta.KmetaLexer
import io.karpfen.dsl.grammar.kmodel.KmodelLexer
import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.antlr.EmbeddedKind
import io.karpfen.ide.core.document.antlr.EmbeddedPiece
import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.Vocabulary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests of the collaborators the three tokenizers are built from. */
class TokenizerBuildingBlocksTest {

    @Test
    fun `token sequences drop null and empty ranges and clip overlaps`() {
        val sequence = TokenSequence()

        sequence.add(TextRange(0, 3), TokenKind.KEYWORD)
        sequence.add(null, TokenKind.ERROR)
        sequence.add(TextRange.empty(4), TokenKind.ERROR)
        sequence.add(TextRange(2, 6), TokenKind.STRING)
        sequence.add(TextRange(4, 6), TokenKind.NUMBER)

        assertEquals(
            listOf(SyntaxToken(TextRange(0, 3), TokenKind.KEYWORD), SyntaxToken(TextRange(3, 6), TokenKind.STRING)),
            sequence.toList(),
        )
    }

    @Test
    fun `the overlap index finds overlaps among unsorted and nested ranges`() {
        val index = RangeOverlapIndex(listOf(TextRange(20, 30), TextRange(0, 50), TextRange(60, 61), TextRange.empty(70)))

        assertTrue(index.overlapsAny(TextRange(45, 55)))
        assertTrue(index.overlapsAny(TextRange(60, 61)))
        assertFalse(index.overlapsAny(TextRange(50, 60)))
        assertFalse(index.overlapsAny(TextRange(61, 80)))
        assertFalse(index.overlapsAny(TextRange.empty(25)))
        assertFalse(RangeOverlapIndex(emptyList()).overlapsAny(TextRange(0, 10)))
        assertTrue(RangeOverlapIndex(listOf(TextRange(5, 6))).overlapsAny(TextRange(0, 10)))
    }

    @Test
    fun `token kind tables classify, fall back and reject duplicates`() {
        val table = TokenKindTable.of(TokenKind.KEYWORD to intArrayOf(1, 2), TokenKind.STRING to intArrayOf(3))

        assertEquals(TokenKind.KEYWORD, table.kindOf(2))
        assertEquals(TokenKind.ERROR, table.kindOf(9))
        assertTrue(table.classifies(3))
        assertFalse(table.classifies(9))
        assertEquals(TokenKind.IDENTIFIER, TokenKindTable.of(fallback = TokenKind.IDENTIFIER).kindOf(1))
        assertFailsWith<IllegalArgumentException> {
            TokenKindTable.of(TokenKind.KEYWORD to intArrayOf(1), TokenKind.STRING to intArrayOf(1))
        }
    }

    @Test
    fun `every token type of the three vocabularies is classified except skipped ones and the catch-all`() {
        fun unclassified(vocabulary: Vocabulary, table: TokenKindTable): Set<String> =
            (1..vocabulary.maxTokenType).filterNot(table::classifies).map(vocabulary::getSymbolicName).toSet()

        assertEquals(setOf("WS", "COMMENT"), unclassified(KmetaLexer.VOCABULARY, KMETA_TOKEN_KINDS))
        assertEquals(setOf("WS", "COMMENT"), unclassified(KmodelLexer.VOCABULARY, KMODEL_TOKEN_KINDS))
        assertEquals(setOf("WS", "SYMBOL"), unclassified(KstatesLexer.VOCABULARY, KSTATES_TOKEN_KINDS))
    }

    @Test
    fun `the fail-safe guard turns an exception into error tokens and an internal-error diagnostic`() {
        val failing = Tokenizer { error("boom") }

        val result = FailSafeTokenizer(KarpfenLanguage.KSTATES, failing).tokenize(TextSnapshot("ab  c\nd", 3))

        assertEquals(3, result.version)
        assertEquals(listOf(TextRange(0, 2), TextRange(4, 5), TextRange(6, 7)), result.tokens.map { it.range })
        assertTrue(result.tokens.all { it.kind == TokenKind.ERROR })
        val diagnostic = result.diagnostics.single()
        assertEquals(DiagnosticCodes.INTERNAL_ERROR, diagnostic.code)
        assertEquals(DiagnosticSource.IDE, diagnostic.source)
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertTrue("boom" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `the fail-safe guard passes results of a working tokenizer through`() {
        val snapshot = TextSnapshot("type", 1)
        val expected = KmetaTokenizer().tokenize(snapshot)

        assertEquals(expected, FailSafeTokenizer(KarpfenLanguage.KMETA, KmetaTokenizer()).tokenize(snapshot))
    }

    @Test
    fun `embedded pieces map to token kinds and only double slashes warn`() {
        assertEquals(
            mapOf(
                EmbeddedKind.CODE to TokenKind.EMBEDDED_CODE, EmbeddedKind.KEYWORD to TokenKind.EMBEDDED_KEYWORD,
                EmbeddedKind.STRING to TokenKind.EMBEDDED_STRING, EmbeddedKind.NUMBER to TokenKind.EMBEDDED_NUMBER,
                EmbeddedKind.COMMENT to TokenKind.EMBEDDED_COMMENT, EmbeddedKind.KARPFEN_COMMENT to TokenKind.COMMENT,
                EmbeddedKind.PATH_REFERENCE to TokenKind.PATH_REFERENCE,
                EmbeddedKind.MACRO_REFERENCE to TokenKind.MACRO_REFERENCE,
            ),
            EmbeddedKind.entries.associateWith(EmbeddedPieceTokens::kindOf),
        )
        val warning = EmbeddedPieceTokens.diagnosticOf(EmbeddedPiece(TextRange(4, 9), EmbeddedKind.KARPFEN_COMMENT))
        assertEquals(TextRange(4, 9), warning?.range)
        assertEquals(DiagnosticCodes.EVAL_DOUBLE_SLASH, warning?.code)
        EmbeddedKind.entries.filter { it != EmbeddedKind.KARPFEN_COMMENT }.forEach { kind ->
            assertNull(EmbeddedPieceTokens.diagnosticOf(EmbeddedPiece(TextRange(0, 1), kind)), "$kind")
        }
    }

    /**
     * In `WITH MACRO("m") AS "d" {` the macro call does not replace the pending WITH; a macro definition opens another
     * block; unbalanced closing braces are ignored.
     */
    @Test
    fun `the block tracker decides EVENT by the innermost block`() {
        val tracker = KstatesBlockTracker()
        assertEquals(TokenKind.KEYWORD, tracker.eventKind())

        listOf(KstatesLexer.DO, KstatesLexer.LBRACE).forEach(tracker::accept)
        assertEquals(TokenKind.OPERATION, tracker.eventKind())

        listOf(KstatesLexer.WITH, KstatesLexer.MACRO, KstatesLexer.LPAREN, KstatesLexer.RPAREN, KstatesLexer.AS, KstatesLexer.LBRACE)
            .forEach(tracker::accept)
        assertEquals(TokenKind.OPERATION, tracker.eventKind())

        listOf(KstatesLexer.RBRACE, KstatesLexer.RBRACE, KstatesLexer.CONDITION, KstatesLexer.LBRACE).forEach(tracker::accept)
        assertEquals(TokenKind.KEYWORD, tracker.eventKind())

        listOf(KstatesLexer.RBRACE, KstatesLexer.RBRACE, KstatesLexer.RBRACE, KstatesLexer.MACRO, KstatesLexer.STRING, KstatesLexer.LBRACE)
            .forEach(tracker::accept)
        assertEquals(TokenKind.KEYWORD, tracker.eventKind())
    }
}
