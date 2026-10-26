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

import io.karpfen.ide.core.document.antlr.CodePointOffsets
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.antlr.IdeErrorListener
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.antlr.NestingDepth
import io.karpfen.ide.core.document.parse.NestingLimit
import io.karpfen.ide.core.document.parse.NestingLimitExceeded
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenStream

/**
 * Runs a dsl-tools parser the IDE way: a `CommonTokenStream` over [KarpfenLexers.create], one
 * [IdeErrorListener] attached to the lexer and the parser (no console output), ANTLR's default error recovery, so
 * broken text still yields a partial tree, and no parse at all of a text beyond the [NestingLimit]. The generated
 * parser and its entry rule are the per-language strategy.
 *
 * Stateless and thread-safe (every call creates its own lexer, stream and parser; the generated parsers share only
 * ANTLR's thread-safe prediction caches).
 *
 * @param grammar selects the generated lexer.
 * @param newParser creates the generated parser over a token stream, e.g. `::KmetaParser`.
 * @param entryRule invokes the grammar's entry rule, e.g. `KmetaParser::kmeta_file`.
 */
internal class TreeParser<P : Parser, T : ParserRuleContext>(
    private val grammar: DslGrammar,
    private val newParser: (TokenStream) -> P,
    private val entryRule: (P) -> T,
) {
    /**
     * Parses [snapshot]; errors become the tree's diagnostics. The stream is filled before the parse, which then reads
     * the tokens from it: the nesting is measured on every token first, and the walkers and the EVAL scanner need every
     * token, also where error recovery stops before the end of the input.
     *
     * @throws NestingLimitExceeded instead of parsing a text that nests deeper than [NestingLimit.MAX_DEPTH]; never
     *   throws otherwise.
     */
    fun parse(snapshot: TextSnapshot): ParsedTree<T> {
        val offsets = CodePointOffsets.of(snapshot.text)
        val listener = IdeErrorListener(offsets)
        val stream = CommonTokenStream(KarpfenLexers.create(grammar, CharStreams.fromString(snapshot.text), listener))
        stream.fill()
        requireWithinNestingLimit(stream.tokens, offsets)
        val root = entryRule(listener.attachTo(newParser(stream)))
        return ParsedTree(snapshot, offsets, stream.tokens.toList(), root, listener.diagnostics)
    }

    /** Throws [NestingLimitExceeded] at the first brace among [tokens] that opens a level beyond the limit, if any. */
    private fun requireWithinNestingLimit(tokens: List<Token>, offsets: CodePointOffsets) {
        val beyond = NestingDepth.firstBeyond(tokens, grammar, NestingLimit.MAX_DEPTH) ?: return
        throw NestingLimitExceeded(checkNotNull(offsets.range(beyond.startIndex, beyond.stopIndex)))
    }
}
