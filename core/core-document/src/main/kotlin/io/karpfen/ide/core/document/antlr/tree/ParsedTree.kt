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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.antlr.CodePointOffsets
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * The result of one ANTLR parse by [TreeParser], with the range arithmetic that every tree walker needs. It gives the
 * IDE ranges of tokens and rule contexts, converting code points with [offsets], and the strings a rule matched.
 *
 * Broken text yields partial trees. Rule contexts may lack children. The parser conjures "missing" tokens with start
 * index -1 and adds them as error nodes. It also keeps the tokens it skipped during recovery as error nodes. The
 * accessors here hide both. A conjured token has no range, so [rangeOf] answers null. [terminals] drops the skipped
 * tokens and keeps the conjured ones at their grammar position. The object is immutable after construction and stays
 * inside one analysis.
 *
 * @property snapshot the parsed text.
 * @property offsets the conversion of the parser's code-point indices into offsets of the text.
 * @property tokens every token of the parser's stream, of every channel, in input order and ending with EOF.
 * @property root the tree of the grammar's entry rule.
 * @property diagnostics LEXER and PARSER errors reported while parsing, sorted with [Diagnostic.ORDER].
 */
internal class ParsedTree<out T : ParserRuleContext>(
    val snapshot: TextSnapshot,
    val offsets: CodePointOffsets,
    val tokens: List<Token>,
    val root: T,
    val diagnostics: List<Diagnostic>,
) {
    /** The parsed text. */
    val text: String get() = snapshot.text

    /** The IDE range of [token]. Null for a conjured token or no token. EOF is the empty range at the end. */
    fun rangeOf(token: Token?): TextRange? = token?.let { offsets.range(it.startIndex, it.stopIndex) }

    /** The IDE range of the token of [node]. Null for a conjured token or no node. */
    fun rangeOf(node: TerminalNode?): TextRange? = rangeOf(node?.symbol)

    /**
     * The range from the first to the last token that [ctx] consumed. A rule that consumed nothing, because it failed
     * on its first token, yields the empty range at its start token.
     */
    fun rangeOf(ctx: ParserRuleContext): TextRange {
        val start = rangeOf(ctx.start) ?: return TextRange.empty(0)
        val stop = ctx.stop
        if (stop == null || stop.tokenIndex < ctx.start.tokenIndex) return TextRange.empty(start.start)
        val end = rangeOf(stop)?.end ?: return TextRange.empty(start.start)
        return TextRange(start.start, maxOf(start.start, end))
    }

    /**
     * The terminal children of [ctx] with token [type], in order. They are the tokens the rule matched and the tokens
     * the parser conjured for missing input, whose [rangeOf] is null. The tokens that error recovery skipped are left
     * out. Empty for a null [ctx].
     */
    fun terminals(ctx: ParserRuleContext?, type: Int): List<TerminalNode> {
        val children = ctx?.children ?: return emptyList()
        return children.filterIsInstance<TerminalNode>().filter { node ->
            node.symbol.type == type && (node !is ErrorNode || isConjured(node.symbol))
        }
    }

    /** The first of [terminals], or null if there is none. */
    fun terminal(ctx: ParserRuleContext?, type: Int): TerminalNode? = terminals(ctx, type).firstOrNull()

    /** The string literal of [node], which is a matched STRING token. Null for a missing or conjured token. */
    fun quoted(node: TerminalNode?): QuotedString? {
        val whole = rangeOf(node) ?: return null
        return QuotedString.of(text, whole)
    }

    /** The string literals of [terminals] with [type] in [ctx]. A conjured or missing string is a null entry. */
    fun quotedStrings(ctx: ParserRuleContext?, type: Int): List<QuotedString?> = terminals(ctx, type).map(::quoted)

    /**
     * What to select for a construct named by [name]. It is the name inside its quotes. While the name is missing, it
     * is the empty range at the start of [ctx].
     */
    fun selectionOf(name: QuotedString?, ctx: ParserRuleContext): TextRange = name?.inner ?: TextRange.empty(rangeOf(ctx).start)

    /** True for a token that the parser invented for missing input. Such a token has no position in the text. */
    private fun isConjured(token: Token): Boolean = token.startIndex < 0
}
