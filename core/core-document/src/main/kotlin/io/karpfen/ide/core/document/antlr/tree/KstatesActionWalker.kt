package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import kstates.KstatesParser
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Walks the action blocks of a KStates state (ENTRY, DO and the blocks nested in them), spec §3.3:
 * - action rules: the first argument is a MODEL_PATH (`SET("a->b", …)`, `DROPOBJ("p")`, `SETOBJ("src", …)`); the
 *   relation argument of `SETOBJ`/`APPENDOBJ` is a RELATION_KEY reference qualified with the source path (PROPERTY_NAME
 *   span); `EVENT("d", "n")` as an action is an event domain and name; a `MACRO(…)` right side is a macro call. String
 *   right sides are values, not references; EVAL right sides are read lexically (see [EmbeddedCode]);
 * - `IF IN SCOPE("p", …) { … }`: every string is a MODEL_PATH; its block is walked in place;
 * - `WITH MACRO(…) AS "x" { … }`: a macro call plus a BINDING declaration (container: the state; BINDING_NAME span)
 *   and an outline node (detail: the macro name) whose children are the bindings of its block.
 */
internal class KstatesActionWalker(
    private val tree: ParsedTree<*>,
    private val collector: SyntaxCollector,
    private val references: KstatesReferences,
) {
    /** Walks [block] of the state named [state]; returns the outline nodes of its WITH bindings, in document order. */
    fun walk(block: KstatesParser.Action_blockContext?, state: String): List<OutlineNode> =
        block?.action_item().orEmpty().flatMap { item(it, state) }

    private fun item(ctx: KstatesParser.Action_itemContext, state: String): List<OutlineNode> {
        ctx.action_rule()?.let { rule(it) }
        ctx.in_scope_block()?.let { scope ->
            tree.quotedStrings(scope, KstatesParser.STRING).forEach(references::modelPath)
            return walk(scope.action_block(), state)
        }
        ctx.with_block()?.let { return listOf(binding(it, state)) }
        return emptyList()
    }

    private fun rule(ctx: KstatesParser.Action_ruleContext) {
        val operation = operationOf(ctx.action_operation())
        val arguments = tree.quotedStrings(ctx, KstatesParser.STRING)
        val rightSide = ctx.action_right_side()
        if (operation == KstatesParser.EVENT) {
            references.event(arguments.getOrNull(0), arguments.getOrNull(1) ?: tree.quoted(tree.terminal(rightSide, KstatesParser.STRING)))
        } else {
            val source = arguments.getOrNull(0)
            references.modelPath(source)
            if (operation != null && KstatesParser.VOCABULARY.getSymbolicName(operation) in KarpfenSyntax.KSTATES_RELATION_OPERATIONS) {
                val qualifier = source?.let { KarpfenSyntax.normalizeModelPath(it.value) }
                collector.reference(ReferenceKind.RELATION_KEY, arguments.getOrNull(1), qualifier, SemanticKind.PROPERTY_NAME)
            }
        }
        rightSide?.macro_call()?.let(references::macroCall)
    }

    private fun binding(ctx: KstatesParser.With_blockContext, state: String): OutlineNode {
        val macro = references.macroCall(ctx.macro_call())
        val name = tree.quoted(tree.terminal(ctx, KstatesParser.STRING))
        val nested = walk(ctx.action_block(), state)
        val node = outlineNode(SymbolKind.BINDING, name?.value.orEmpty(), macro, tree.rangeOf(ctx), tree.selectionOf(name, ctx), nested)
        collector.declare(SymbolKind.BINDING, name, node.range, container = state, span = SemanticKind.BINDING_NAME)
        return node
    }

    /** The token type of the operation keyword (`SET`, `EVENT`, …), null if it is missing. */
    private fun operationOf(ctx: KstatesParser.Action_operationContext?): Int? =
        ctx?.children.orEmpty().filterIsInstance<TerminalNode>().firstOrNull { it !is ErrorNode }?.symbol?.type
}
