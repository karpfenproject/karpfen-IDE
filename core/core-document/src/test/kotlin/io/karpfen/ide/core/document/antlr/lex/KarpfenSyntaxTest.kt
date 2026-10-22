package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kstates.KstatesLexer

/** [KarpfenSyntax], the one home of the lexical facts other layers read, checked against the generated lexers. */
class KarpfenSyntaxTest {

    @Test
    fun `the KStates operations are exactly the lexer tokens the tokenizer classifies as OPERATION`() {
        val vocabulary = KstatesLexer.VOCABULARY
        val operations = (1..vocabulary.maxTokenType)
            .filter { KSTATES_TOKEN_KINDS.classifies(it) && KSTATES_TOKEN_KINDS.kindOf(it) == TokenKind.OPERATION }
            .map { vocabulary.getSymbolicName(it) }

        assertEquals(KarpfenSyntax.KSTATES_OPERATIONS, operations.toSet())
        assertTrue(KarpfenSyntax.KSTATES_OPERATIONS.containsAll(KarpfenSyntax.KSTATES_RELATION_OPERATIONS))
    }

    @Test
    fun `the KStates block keywords are keyword tokens of the lexer, each of one kind`() {
        val vocabulary = KstatesLexer.VOCABULARY
        val keywords = (1..vocabulary.maxTokenType)
            .filter { KSTATES_TOKEN_KINDS.classifies(it) && KSTATES_TOKEN_KINDS.kindOf(it) == TokenKind.KEYWORD }
            .map { vocabulary.getSymbolicName(it) }
            .toSet()
        val blocks = listOf(KarpfenSyntax.KSTATES_ACTION_BLOCKS, setOf(KarpfenSyntax.KSTATES_CONDITION_BLOCK), KarpfenSyntax.KSTATES_STRUCTURE_BLOCKS)

        assertTrue(keywords.containsAll(blocks.flatten()), "not keywords: ${blocks.flatten() - keywords}")
        assertEquals(blocks.sumOf { it.size }, blocks.flatten().toSet().size, "a keyword opens one kind of block")
    }

    @Test
    fun `model paths are named without blanks and split at the separator`() {
        assertEquals("self->room->robot", KarpfenSyntax.normalizeModelPath(" self ->\troom-> robot "))
        assertTrue(KarpfenSyntax.isPathSeparatorAt("a->b", 1))
        assertFalse(KarpfenSyntax.isPathSeparatorAt("a->b", 1, limit = 2), "the separator must end before the limit")
        assertFalse(KarpfenSyntax.isPathSeparatorAt("a-b", 1))
        assertFalse(KarpfenSyntax.isPathSeparatorAt("a-", 1))
        assertEquals(listOf(true, true, false, false), listOf(' ', '\t', '\n', '\r').map(KarpfenSyntax::isBlank))
    }

    @Test
    fun `the unary and relation operations are disjoint operations`() {
        assertTrue(KarpfenSyntax.KSTATES_OPERATIONS.containsAll(KarpfenSyntax.KSTATES_UNARY_OPERATIONS))
        assertTrue(KarpfenSyntax.KSTATES_UNARY_OPERATIONS.none { it in KarpfenSyntax.KSTATES_RELATION_OPERATIONS })
    }

    @Test
    fun `the identifier characters are the ones that continue a KStates identifier`() {
        for (code in 0x21 until 0x250) {
            val char = code.toChar()
            val tokens = KarpfenLexers.lex(DslGrammar.KSTATES, "q$char").tokens
            val continues = tokens.size == 1 && tokens.single().type == KstatesLexer.IDENT

            assertEquals(continues, KarpfenSyntax.isIdentifierChar(char), "'$char' (U+${code.toString(16).padStart(4, '0')})")
        }
    }
}
