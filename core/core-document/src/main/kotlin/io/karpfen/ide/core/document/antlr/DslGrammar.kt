package io.karpfen.ide.core.document.antlr

import io.karpfen.dsl.grammar.kmeta.KmetaLexer
import io.karpfen.dsl.grammar.kmodel.KmodelLexer
import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.ide.core.document.KarpfenLanguage
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.Lexer

/**
 * The ANTLR grammars of karpfen-dsl-tools, one per modeling language, each with its generated lexer. The only place
 * that maps a language to its generated lexer class; environment descriptors have no grammar here, because the JSON
 * tokenizer of the `json` package reads them.
 *
 * @property language the language the grammar describes.
 * @param newLexer creates the generated lexer over a character stream.
 */
internal enum class DslGrammar(val language: KarpfenLanguage, private val newLexer: (CharStream) -> Lexer) {
    /** KMeta, lexed by the generated [KmetaLexer]. */
    KMETA(KarpfenLanguage.KMETA, ::KmetaLexer),

    /** KModel, lexed by the generated [KmodelLexer]. */
    KMODEL(KarpfenLanguage.KMODEL, ::KmodelLexer),

    /** KStates, lexed by the generated [KstatesLexer]. */
    KSTATES(KarpfenLanguage.KSTATES, ::KstatesLexer),
    ;

    /** A new generated lexer of this grammar over [input], with ANTLR's default listeners. */
    fun lexer(input: CharStream): Lexer = newLexer(input)
}
