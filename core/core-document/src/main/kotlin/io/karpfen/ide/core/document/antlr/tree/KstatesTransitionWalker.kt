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

import io.karpfen.dsl.grammar.kstates.KstatesParser
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Walks the `TRANSITIONS { }` block of a KStates tree. Every state string of a TRANSITION, SPLIT or JOIN becomes a
 * STATE reference with a STATE_NAME span. Each transition becomes an outline node, but not a declaration. The node is
 * named `a -> b`, `a -> b, c` or `a, b -> c` and selects its state strings.
 *
 * The strings before the arrow are the sources. While the arrow is missing, the shape of the grammar decides. The last
 * string of a JOIN is its target, and the first string of the other transitions is their source.
 *
 * In conditions, `EVENT("d", "n")` names an event domain and an event, and `VALUE("p")` names a MODEL_PATH. Both are
 * recorded by [KstatesReferences]. EVAL conditions are read lexically by [EmbeddedCode].
 */
internal class KstatesTransitionWalker(
    private val tree: ParsedTree<*>,
    private val collector: SyntaxCollector,
    private val references: KstatesReferences,
) {
    /** The outline nodes of the transitions of [block], in document order. */
    fun walk(block: KstatesParser.Transitions_blockContext?): List<OutlineNode> =
        block?.transition_definition().orEmpty().mapNotNull(::transition)

    /** The outline node of a TRANSITION, SPLIT or JOIN. Null for a definition that the parser could not classify. */
    private fun transition(ctx: KstatesParser.Transition_definitionContext): OutlineNode? {
        ctx.normal_transition()?.let { return transition(SymbolKind.TRANSITION, it, KstatesParser.TRANSITION, it.condition_block()) }
        ctx.split_transition()?.let { return transition(SymbolKind.SPLIT, it, KstatesParser.SPLIT, it.condition_block()) }
        ctx.join_transition()?.let { return transition(SymbolKind.JOIN, it, KstatesParser.JOIN, it.condition_block()) }
        return null
    }

    /** Records the state references and conditions of one transition and returns its outline node. */
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

    /** Records the references of an `EVENT("d", "n")` or `VALUE("p")` condition clause. */
    private fun clause(ctx: KstatesParser.Condition_clauseContext) {
        ctx.event_condition()?.let { event ->
            val strings = tree.quotedStrings(event, KstatesParser.STRING)
            references.event(strings.getOrNull(0), strings.getOrNull(1))
        }
        ctx.value_condition()?.let { value -> references.modelPath(tree.quoted(tree.terminal(value, KstatesParser.STRING))) }
    }

    /** Splits the state strings into sources and targets at the arrow, as the class documentation describes. */
    private fun sides(kind: SymbolKind, states: List<TerminalNode>, arrow: TerminalNode?): Pair<List<TerminalNode>, List<TerminalNode>> {
        val arrowIndex = arrow?.symbol?.tokenIndex ?: -1
        return when {
            arrowIndex >= 0 -> states.partition { it.symbol.tokenIndex in 0 until arrowIndex }
            kind == SymbolKind.JOIN -> states.dropLast(1) to states.takeLast(1)
            else -> states.take(1) to states.drop(1)
        }
    }

    /** The state names of [states], separated by commas. Missing strings, which ANTLR conjured, are left out. */
    private fun names(states: List<TerminalNode>): String = states.mapNotNull { tree.quoted(it)?.value }.joinToString(", ")
}
