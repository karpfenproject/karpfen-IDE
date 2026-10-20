package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import kstates.KstatesParser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Walks the `TRANSITIONS { … }` block of a KStates tree (spec §3.3): every state string of a TRANSITION, SPLIT or JOIN
 * is a STATE reference (STATE_NAME span); each transition is an outline node (no declaration) named `a -> b`,
 * `a -> b, c` or `a, b -> c` and selecting its state strings. The strings before the arrow are the sources; while the
 * arrow is missing, the grammar's shape decides (the last string of a JOIN is its target, the first string of the others
 * its source). Conditions: `EVENT("d", "n")` → event domain and name, `VALUE("p")` → MODEL_PATH (see
 * [KstatesReferences]); EVAL conditions are read lexically (see [EmbeddedCode]).
 */
internal class KstatesTransitionWalker(
    private val tree: ParsedTree<*>,
    private val collector: SyntaxCollector,
    private val references: KstatesReferences,
) {
    /** The outline nodes of the transitions of [block], in document order. */
    fun walk(block: KstatesParser.Transitions_blockContext?): List<OutlineNode> =
        block?.transition_definition().orEmpty().mapNotNull(::transition)

    private fun transition(ctx: KstatesParser.Transition_definitionContext): OutlineNode? {
        ctx.normal_transition()?.let { return transition(SymbolKind.TRANSITION, it, KstatesParser.TRANSITION, it.condition_block()) }
        ctx.split_transition()?.let { return transition(SymbolKind.SPLIT, it, KstatesParser.SPLIT, it.condition_block()) }
        ctx.join_transition()?.let { return transition(SymbolKind.JOIN, it, KstatesParser.JOIN, it.condition_block()) }
        return null
    }

    private fun transition(
        kind: SymbolKind,
        ctx: ParserRuleContext,
        keywordType: Int,
        condition: KstatesParser.Condition_blockContext?,
    ): OutlineNode {
        val states = tree.terminals(ctx, KstatesParser.STRING)
        states.forEach { collector.reference(ReferenceKind.STATE, tree.quoted(it), span = SemanticKind.STATE_NAME) }
        condition?.condition_clause().orEmpty().forEach(::clause)
        val (sources, targets) = sides(kind, states, tree.terminal(ctx, KstatesParser.ARROW))
        val name = names(sources) + " -> " + names(targets)
        val range = tree.rangeOf(ctx)
        val stateRanges = states.mapNotNull { tree.rangeOf(it) }
        val selection = stateRanges.takeIf { it.isNotEmpty() }?.let { it.first().union(it.last()) }
            ?: tree.rangeOf(tree.terminal(ctx, keywordType))
            ?: TextRange.empty(range.start)
        return outlineNode(kind, name, null, range, selection)
    }

    private fun clause(ctx: KstatesParser.Condition_clauseContext) {
        ctx.event_condition()?.let { event ->
            val strings = tree.quotedStrings(event, KstatesParser.STRING)
            references.event(strings.getOrNull(0), strings.getOrNull(1))
        }
        ctx.value_condition()?.let { value -> references.modelPath(tree.quoted(tree.terminal(value, KstatesParser.STRING))) }
    }

    /** Splits the state strings into sources and targets at the arrow (see the class documentation). */
    private fun sides(kind: SymbolKind, states: List<TerminalNode>, arrow: TerminalNode?): Pair<List<TerminalNode>, List<TerminalNode>> {
        val arrowIndex = arrow?.symbol?.tokenIndex ?: -1
        return when {
            arrowIndex >= 0 -> states.partition { it.symbol.tokenIndex in 0 until arrowIndex }
            kind == SymbolKind.JOIN -> states.dropLast(1) to states.takeLast(1)
            else -> states.take(1) to states.drop(1)
        }
    }

    /** The state names of [states], comma-separated; missing (conjured) strings are left out. */
    private fun names(states: List<TerminalNode>): String = states.mapNotNull { tree.quoted(it)?.value }.joinToString(", ")
}
