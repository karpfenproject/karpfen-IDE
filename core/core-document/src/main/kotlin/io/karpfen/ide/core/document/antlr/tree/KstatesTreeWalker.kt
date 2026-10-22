package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import kstates.KstatesParser

/**
 * Walks a KStates tree into one STATEMACHINE outline node whose children are the states, the transitions
 * and the macros, in document order (the `STATES`, `TRANSITIONS` and `MACROS` blocks themselves are not nodes). The
 * machine is declared as STATEMACHINE named after its keyword (`STATEMACHINE`, selecting the keyword) with the
 * `ATTACHED TO` type as type name and outline detail; that type string is a type reference.
 *
 * Coordinates one walker per block ([KstatesStateWalker] with [KstatesActionWalker], [KstatesTransitionWalker],
 * [KstatesMacroWalker]) over the shared [KstatesReferences] rules; the walkers are the structure of this one walk,
 * created per analysis.
 */
internal class KstatesTreeWalker(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    private val references = KstatesReferences(tree, collector)
    private val states = KstatesStateWalker(tree, collector, KstatesActionWalker(tree, collector, references))
    private val transitions = KstatesTransitionWalker(tree, collector, references)
    private val macros = KstatesMacroWalker(tree, collector)

    /** The outline of [file]: the state machine, if the text has one. */
    fun walk(file: KstatesParser.Kstates_fileContext): List<OutlineNode> = listOfNotNull(file.statemachine()?.let(::machine))

    /** Records the state machine of [ctx] and returns its outline node. */
    private fun machine(ctx: KstatesParser.StatemachineContext): OutlineNode {
        val attachedTo = tree.quoted(tree.terminal(ctx, KstatesParser.STRING))
        collector.typeReference(attachedTo)
        val children = states.walk(ctx.states_block()) + transitions.walk(ctx.transitions_block()) + macros.walk(ctx.macros_block())
        val range = tree.rangeOf(ctx)
        val keyword = tree.rangeOf(tree.terminal(ctx, KstatesParser.STATEMACHINE)) ?: TextRange.empty(range.start)
        val node = outlineNode(SymbolKind.STATEMACHINE, MACHINE_NAME, attachedTo?.value, range, keyword, children)
        collector.declare(SymbolKind.STATEMACHINE, MACHINE_NAME, keyword, node.range, container = null, typeName = attachedTo?.value)
        return node
    }

    private companion object {
        /** The name of the STATEMACHINE declaration and outline node: its keyword (the grammar gives it no name). */
        const val MACHINE_NAME = "STATEMACHINE"
    }
}
