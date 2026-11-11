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
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.ParserRuleContext

/**
 * Walks the `STATES { }` block of a KStates tree. Every `STATE "s" { }`, including `INITIAL STATE`, becomes a STATE
 * declaration with a STATE_NAME span. Its container is the parent state, or null at the top level. It also gets an
 * outline node. The children of that node are its ENTRY and DO block nodes and its nested states. A block node is
 * named `ENTRY` or `DO` and selects its keyword. Its children are the WITH bindings that [KstatesActionWalker] finds.
 */
internal class KstatesStateWalker(
    private val tree: ParsedTree<*>,
    private val collector: SyntaxCollector,
    private val actions: KstatesActionWalker,
) {
    /** The outline nodes of the top-level states of [block], in document order. */
    fun walk(block: KstatesParser.States_blockContext?): List<OutlineNode> =
        block?.state_definition().orEmpty().map { state(it, parent = null) }

    /** Records the state of [ctx], nested in the state named [parent], and returns its outline node. */
    private fun state(ctx: KstatesParser.State_definitionContext, parent: String?): OutlineNode {
        val name = tree.quoted(tree.terminal(ctx, KstatesParser.STRING))
        val stateName = name?.value.orEmpty()
        val blocks = listOfNotNull(
            ctx.entry_block()?.let { block(SymbolKind.ENTRY_BLOCK, "ENTRY", it, KstatesParser.ENTRY, it.action_block(), stateName) },
            ctx.do_block()?.let { block(SymbolKind.DO_BLOCK, "DO", it, KstatesParser.DO, it.action_block(), stateName) },
        )
        val children = blocks + ctx.state_definition().map { state(it, stateName) }
        val node = outlineNode(SymbolKind.STATE, stateName, null, tree.rangeOf(ctx), tree.selectionOf(name, ctx), children)
        collector.declare(SymbolKind.STATE, name, node.range, container = parent, span = SemanticKind.STATE_NAME)
        return node
    }

    /**
     * The outline node of an ENTRY or DO block. It is named after its keyword and selects it, and its children are the
     * WITH bindings of the block.
     */
    private fun block(
        kind: SymbolKind,
        keywordText: String,
        ctx: ParserRuleContext,
        keywordType: Int,
        actionBlock: KstatesParser.Action_blockContext?,
        state: String,
    ): OutlineNode {
        val range = tree.rangeOf(ctx)
        val keyword = tree.terminal(ctx, keywordType)
        val selection = tree.rangeOf(keyword) ?: TextRange.empty(range.start)
        return outlineNode(kind, keywordText, null, range, selection, actions.walk(actionBlock, state))
    }
}
