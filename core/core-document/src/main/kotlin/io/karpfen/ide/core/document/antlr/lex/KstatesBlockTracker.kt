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

import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.ide.core.document.syntax.KstatesBlockHeader
import io.karpfen.ide.core.document.syntax.KstatesBlockKind
import io.karpfen.ide.core.document.syntax.TokenKind

/**
 * Follows the block structure of a KStates document on its brace tokens to classify `EVENT`: an action
 * ([TokenKind.OPERATION]) directly inside an action block, a [TokenKind.KEYWORD] in a CONDITION block and anywhere
 * else (also in broken text, where the block is unknown). A block's kind is the one rule of [KstatesBlockHeader], read
 * from the tokens since the previous brace.
 *
 * One instance per tokenization; fed with the default-channel tokens outside EVAL bodies in document order (the
 * body's own `{` and `}` included, so its inner braces, which the tokenizer skips, cannot unbalance the stack).
 */
internal class KstatesBlockTracker {

    private val open = ArrayDeque<KstatesBlockKind>()
    private var header = KstatesBlockHeader()

    /** Feeds the next default-channel token of type [tokenType]. */
    fun accept(tokenType: Int) {
        when (tokenType) {
            KstatesLexer.LBRACE -> {
                open.addLast(header.kind)
                header = KstatesBlockHeader()
            }
            KstatesLexer.RBRACE -> {
                open.removeLastOrNull()
                header = KstatesBlockHeader()
            }
            else -> header.accept(WORDS.getOrNull(tokenType))
        }
    }

    /** The kind of an `EVENT` token at the current position. */
    fun eventKind(): TokenKind = if (open.lastOrNull() == KstatesBlockKind.ACTION) TokenKind.OPERATION else TokenKind.KEYWORD

    private companion object {
        /** The header word of each token type: the introducing keywords (found by name) and the parentheses. */
        val WORDS: Array<String?> = arrayOfNulls<String>(KstatesLexer.VOCABULARY.maxTokenType + 1).also { words ->
            KstatesBlockHeader.INTRODUCERS.forEach { words[kstatesTokenType(it)] = it }
            words[KstatesLexer.LPAREN] = KstatesBlockHeader.OPEN
            words[KstatesLexer.RPAREN] = KstatesBlockHeader.CLOSE
        }
    }
}
