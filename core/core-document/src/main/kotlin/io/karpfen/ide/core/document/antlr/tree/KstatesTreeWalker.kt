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
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange

/**
 * Walks a KStates tree into one STATEMACHINE outline node. Its children are the states, the transitions and the
 * macros, in document order. The `STATES`, `TRANSITIONS` and `MACROS` blocks themselves are not nodes. The machine is
 * declared as a STATEMACHINE named after its keyword `STATEMACHINE`, and the declaration selects that keyword. The
 * `ATTACHED TO` type is its type name and outline detail, and that type string is recorded as a type reference.
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
        /**
         * The name of the STATEMACHINE declaration and outline node. It is the keyword, because the grammar gives the
         * machine no name.
         */
        const val MACHINE_NAME = "STATEMACHINE"
    }
}
