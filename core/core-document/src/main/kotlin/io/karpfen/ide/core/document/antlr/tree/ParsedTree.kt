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
 * The result of one ANTLR parse ([TreeParser]) with the range arithmetic every tree walker needs: IDE ranges of
 * tokens and rule contexts (code points converted with [offsets]) and the strings a rule matched.
 *
 * Broken text yields partial trees: rule contexts may lack children, the parser conjures "missing" tokens (start index
 * -1, added as error nodes) and keeps tokens it skipped during recovery as error nodes. The accessors here hide both:
 * a conjured token has no range ([rangeOf] answers null), and [terminals] drops the skipped tokens while keeping the
 * conjured ones at their grammar position. Immutable after construction; confined to one analysis.
 *
 * @property snapshot the parsed text.
 * @property tokens every token of the parser's stream, every channel, in input order, ending with EOF.
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

    /** The IDE range of [token]; null for a conjured token (or no token). EOF is the empty range at the end. */
    fun rangeOf(token: Token?): TextRange? = token?.let { offsets.range(it.startIndex, it.stopIndex) }

    /** The IDE range of [node]'s token; null for a conjured token (or no node). */
    fun rangeOf(node: TerminalNode?): TextRange? = rangeOf(node?.symbol)

    /**
     * The range from the first to the last token [ctx] consumed. A rule that consumed nothing (it failed on its first
     * token) yields the empty range at its start token.
     */
    fun rangeOf(ctx: ParserRuleContext): TextRange {
        val start = rangeOf(ctx.start) ?: return TextRange.empty(0)
        val stop = ctx.stop
        if (stop == null || stop.tokenIndex < ctx.start.tokenIndex) return TextRange.empty(start.start)
        val end = rangeOf(stop)?.end ?: return TextRange.empty(start.start)
        return TextRange(start.start, maxOf(start.start, end))
    }

    /**
     * The terminal children of [ctx] with token [type], in order: the tokens the rule matched and the tokens the parser
     * conjured for missing input (whose [rangeOf] is null), but not the tokens error recovery skipped. Empty for a
     * null [ctx].
     */
    fun terminals(ctx: ParserRuleContext?, type: Int): List<TerminalNode> {
        val children = ctx?.children ?: return emptyList()
        return children.filterIsInstance<TerminalNode>().filter { node ->
            node.symbol.type == type && (node !is ErrorNode || isConjured(node.symbol))
        }
    }

    /** The first of [terminals]; null if there is none. */
    fun terminal(ctx: ParserRuleContext?, type: Int): TerminalNode? = terminals(ctx, type).firstOrNull()

    /** The string literal of [node] (a matched STRING token); null for a missing or conjured token. */
    fun quoted(node: TerminalNode?): QuotedString? {
        val whole = rangeOf(node) ?: return null
        return QuotedString.of(text, whole)
    }

    /** The string literals of [terminals] with [type] in [ctx]; a conjured or missing string is a null entry. */
    fun quotedStrings(ctx: ParserRuleContext?, type: Int): List<QuotedString?> = terminals(ctx, type).map(::quoted)

    /**
     * What to select for a construct named by [name]: the name inside its quotes, or, while the name is missing, the
     * empty range at the start of [ctx].
     */
    fun selectionOf(name: QuotedString?, ctx: ParserRuleContext): TextRange = name?.inner ?: TextRange.empty(rangeOf(ctx).start)

    private fun isConjured(token: Token): Boolean = token.startIndex < 0
}
