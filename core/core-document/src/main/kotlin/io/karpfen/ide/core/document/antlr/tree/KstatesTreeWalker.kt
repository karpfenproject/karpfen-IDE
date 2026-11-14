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
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange

/**
 * Walks a KStates tree into one STATEMACHINE outline node. Its children are the states, the transitions and the
 * macros, in document order. The `STATES`, `TRANSITIONS` and `MACROS` blocks themselves are not nodes. Every type
 * string of the header is recorded as a type reference. The header has two forms.
 * - `STATEMACHINE ATTACHED TO "T"`: the machine is declared as a STATEMACHINE named after its keyword `STATEMACHINE`,
 *   and the declaration selects that keyword. The type `T` is its type name and outline detail.
 * - `STATEMACHINE "name" ATTACHABLE TO "A", "B"`: the machine is declared as a STATEMACHINE named `name` with a
 *   STATE_MACHINE_NAME span, and the declaration selects the name. Its type name is the first listed type, `A`, which
 *   the model paths of the machine start from in completion and hover. Its outline detail lists every type, as in
 *   `A, B`.
 * The form follows the keyword, as the reader of dsl-tools decides it. A name in front of `ATTACHED` or a missing name
 * in front of `ATTACHABLE` is a finding of that reader, and the walker still declares what the text says.
 *
 * The class coordinates one walker per block over the shared [KstatesReferences] rules. [KstatesStateWalker] walks the
 * states together with [KstatesActionWalker], [KstatesTransitionWalker] walks the transitions, and
 * [KstatesMacroWalker] walks the macros. The walkers make up the structure of this one walk and are created per
 * analysis.
 */
internal class KstatesTreeWalker(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    private val references = KstatesReferences(tree, collector)
    private val states = KstatesStateWalker(tree, collector, KstatesActionWalker(tree, collector, references))
    private val transitions = KstatesTransitionWalker(tree, collector, references)
    private val macros = KstatesMacroWalker(tree, collector)

    /** The outline of [file], which is the state machine if the text has one. */
    fun walk(file: KstatesParser.Kstates_fileContext): List<OutlineNode> = listOfNotNull(file.statemachine()?.let(::machine))

    /** Records the state machine of [ctx] and returns its outline node. */
    private fun machine(ctx: KstatesParser.StatemachineContext): OutlineNode {
        val types = listOfNotNull(tree.quoted(tree.terminal(ctx, KstatesParser.STRING))) +
            tree.quotedStrings(ctx.further_types(), KstatesParser.STRING).filterNotNull()
        types.forEach(collector::typeReference)
        val children = states.walk(ctx.states_block()) + transitions.walk(ctx.transitions_block()) + macros.walk(ctx.macros_block())
        val range = tree.rangeOf(ctx)
        val keyword = tree.rangeOf(tree.terminal(ctx, KstatesParser.STATEMACHINE)) ?: TextRange.empty(range.start)
        if (tree.terminal(ctx, KstatesParser.ATTACHABLE) == null) {
            val attachedTo = types.firstOrNull()?.value
            val node = outlineNode(SymbolKind.STATEMACHINE, MACHINE_NAME, attachedTo, range, keyword, children)
            collector.declare(SymbolKind.STATEMACHINE, MACHINE_NAME, keyword, node.range, container = null, typeName = attachedTo)
            return node
        }
        val name = tree.quoted(tree.terminal(ctx.statemachine_name(), KstatesParser.STRING))
        val detail = types.joinToString(", ") { it.value }.ifEmpty { null }
        val selection = name?.inner ?: keyword
        val node = outlineNode(SymbolKind.STATEMACHINE, name?.value.orEmpty(), detail, range, selection, children)
        if (name == null) {
            collector.declare(SymbolKind.STATEMACHINE, "", keyword, node.range, container = null, typeName = types.firstOrNull()?.value)
        } else {
            collector.declare(
                SymbolKind.STATEMACHINE, name, node.range, container = null, typeName = types.firstOrNull()?.value,
                span = SemanticKind.STATE_MACHINE_NAME,
            )
        }
        return node
    }

    private companion object {
        /**
         * The name of the STATEMACHINE declaration and outline node of an attached state machine. It is the keyword,
         * because that form gives the machine no name.
         */
        const val MACHINE_NAME = SymbolDeclaration.ATTACHED_MACHINE_NAME
    }
}
