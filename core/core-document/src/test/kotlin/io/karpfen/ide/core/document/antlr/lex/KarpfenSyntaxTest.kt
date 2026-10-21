package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.TokenKind
import kstates.KstatesLexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `the identifier characters are the ones that continue a KStates identifier`() {
        for (code in 0x21 until 0x250) {
            val char = code.toChar()
            val tokens = KarpfenLexers.lex(KarpfenLanguage.KSTATES, "q$char").tokens
            val continues = tokens.size == 1 && tokens.single().type == KstatesLexer.IDENT

            assertEquals(continues, KarpfenSyntax.isIdentifierChar(char), "'$char' (U+${code.toString(16).padStart(4, '0')})")
        }
    }
}
