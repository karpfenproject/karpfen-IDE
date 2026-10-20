package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import kstates.KstatesParser
import org.antlr.v4.runtime.ParserRuleContext

/**
 * Walks the `STATES { … }` block of a KStates tree (spec §3.3): every `STATE "s" { … }` (also `INITIAL STATE`) is a
 * STATE declaration (container: the parent state, null at top level) with a STATE_NAME span and an outline node whose
 * children are its ENTRY and DO block nodes (named `ENTRY`/`DO`, selecting the keyword; their children are the WITH
 * bindings, see [KstatesActionWalker]) and its nested states.
 */
internal class KstatesStateWalker(
    private val tree: ParsedTree<*>,
    private val collector: SyntaxCollector,
    private val actions: KstatesActionWalker,
) {
    /** The outline nodes of the top-level states of [block], in document order. */
    fun walk(block: KstatesParser.States_blockContext?): List<OutlineNode> =
        block?.state_definition().orEmpty().map { state(it, parent = null) }

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
