package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.KarpfenLanguage
import kmeta.KmetaLexer
import kmodel.KmodelLexer
import kstates.KstatesLexer
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
    /** KMeta, lexed by `kmeta.KmetaLexer`. */
    KMETA(KarpfenLanguage.KMETA, ::KmetaLexer),

    /** KModel, lexed by `kmodel.KmodelLexer`. */
    KMODEL(KarpfenLanguage.KMODEL, ::KmodelLexer),

    /** KStates, lexed by `kstates.KstatesLexer`. */
    KSTATES(KarpfenLanguage.KSTATES, ::KstatesLexer),
    ;

    /** A new generated lexer of this grammar over [input], with ANTLR's default listeners. */
    fun lexer(input: CharStream): Lexer = newLexer(input)
}
