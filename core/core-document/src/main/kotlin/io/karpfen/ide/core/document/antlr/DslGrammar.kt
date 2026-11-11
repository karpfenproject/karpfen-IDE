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

import io.karpfen.dsl.grammar.kmeta.KmetaLexer
import io.karpfen.dsl.grammar.kmodel.KmodelLexer
import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.ide.core.document.KarpfenLanguage
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.Lexer

/**
 * The ANTLR grammars of karpfen-dsl-tools, one per modeling language, each with its generated lexer. This is the only
 * place that maps a language to its generated lexer class. Environment descriptors have no grammar here, because the
 * JSON tokenizer of the `json` package reads them.
 *
 * @property language the language the grammar describes.
 * @property openBrace the token type of `{`, which opens a block one level deeper.
 * @property closeBrace the token type of `}`, which closes it.
 * @param newLexer creates the generated lexer over a character stream.
 */
internal enum class DslGrammar(
    val language: KarpfenLanguage,
    val openBrace: Int,
    val closeBrace: Int,
    private val newLexer: (CharStream) -> Lexer,
) {
    /** KMeta, lexed by the generated [KmetaLexer]. */
    KMETA(KarpfenLanguage.KMETA, KmetaLexer.LBRACE, KmetaLexer.RBRACE, ::KmetaLexer),

    /** KModel, lexed by the generated [KmodelLexer]. */
    KMODEL(KarpfenLanguage.KMODEL, KmodelLexer.LBRACE, KmodelLexer.RBRACE, ::KmodelLexer),

    /** KStates, lexed by the generated [KstatesLexer]. */
    KSTATES(KarpfenLanguage.KSTATES, KstatesLexer.LBRACE, KstatesLexer.RBRACE, ::KstatesLexer),
    ;

    /** A new generated lexer of this grammar over [input], with ANTLR's default listeners. */
    fun lexer(input: CharStream): Lexer = newLexer(input)
}
