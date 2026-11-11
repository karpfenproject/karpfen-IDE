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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.Token

/**
 * Creates the dsl-tools lexers the way the IDE needs them. ANTLR's console listener is removed and an
 * [IdeErrorListener] is attached, so lexer errors become diagnostics instead of console output. The tokenizers lex with
 * [lex]. The tree walkers build their `CommonTokenStream` on [create].
 */
internal object KarpfenLexers {

    /** The generated lexer of [grammar] over [input], with every error listener replaced by [listener]. */
    fun create(grammar: DslGrammar, input: CharStream, listener: IdeErrorListener): Lexer = listener.attachTo(grammar.lexer(input))

    /** Lexes all of [text] with [grammar]. Never throws. Lexer errors become [LexedText.diagnostics]. */
    fun lex(grammar: DslGrammar, text: String): LexedText {
        val offsets = CodePointOffsets.of(text)
        val listener = IdeErrorListener(offsets)
        val tokens = create(grammar, CharStreams.fromString(text), listener).allTokens.toList()
        return LexedText(text, offsets, tokens, listener.diagnostics)
    }
}

/**
 * The result of lexing one text.
 *
 * @property text the lexed text.
 * @property offsets the conversion of the lexer's code-point indices into offsets of [text].
 * @property tokens every emitted token of every channel in input order, without EOF. Tokens that the grammar skips are
 *   absent. That holds for KMeta and KModel whitespace and comments, which [LexerGaps] recovers. KStates whitespace and
 *   `//` comments are on the HIDDEN channel and therefore present.
 * @property diagnostics LEXER errors, sorted with [Diagnostic.ORDER].
 */
internal class LexedText(
    val text: String,
    val offsets: CodePointOffsets,
    val tokens: List<Token>,
    val diagnostics: List<Diagnostic>,
) {
    /** The IDE range of [token], or null for a conjured token. */
    fun rangeOf(token: Token): TextRange? = offsets.range(token.startIndex, token.stopIndex)
}
