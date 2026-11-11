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
package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.antlr.LexerGaps
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.Token

/**
 * The `//` comments of a KMeta or KModel tree, whose lexers skip them. They are the [LexerGaps.GapKind.COMMENT] pieces
 * that [LexerGaps.scan] finds between the tokens of the stream. The tokenizer turns the same pieces into COMMENT
 * tokens.
 */
internal object GapComments {

    /** The comment ranges of [tree], in document order. */
    fun of(tree: ParsedTree<*>): List<TextRange> {
        val tokenRanges = tree.tokens.filter { it.type != Token.EOF }.mapNotNull { tree.rangeOf(it) }
        return LexerGaps.scan(tree.text, tokenRanges).filter { it.kind == LexerGaps.GapKind.COMMENT }.map { it.range }
    }
}
