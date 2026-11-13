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
package io.karpfen.ide.ui.workbench.outline

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import java.util.IdentityHashMap

/**
 * What the Outline shows of the outline of a document. The outline of a parse holds every construct, down to single
 * properties and blocks. The Outline tool window shows only the elements a model is made of:
 *
 * - of a metamodel its types,
 * - of a model its objects, each below the object that has it,
 * - of a state machine its states, nested as they are, and its transitions, splits and joins,
 * - of an environment descriptor its keys and entries, which is all it has.
 *
 * A node that is not shown gives its place to the shown nodes inside it. So an object appears directly below its
 * owner, without the `has` statement between them, and the states and transitions of a state machine appear at the
 * top, without the state machine around them.
 *
 * @property nodes the shown nodes. A node whose children are all shown is the node of the parse itself. Any other is
 *   a copy with the shown children.
 */
internal class OutlineSelection private constructor(val nodes: List<OutlineNode>, private val originals: IdentityHashMap<OutlineNode, OutlineNode>) {

    /** The node of the parse that the shown node [node] stands for, which is [node] itself where it was not copied. */
    fun original(node: OutlineNode): OutlineNode = originals[node] ?: node

    companion object {
        /** The kinds of node the Outline shows. */
        private val SHOWN: Set<SymbolKind> = setOf(
            SymbolKind.TYPE,
            SymbolKind.OBJECT,
            SymbolKind.STATE,
            SymbolKind.TRANSITION,
            SymbolKind.SPLIT,
            SymbolKind.JOIN,
            SymbolKind.KEY,
            SymbolKind.ENTRY,
        )

        /** What the Outline shows of [outline], the outline of a parse. */
        fun of(outline: List<OutlineNode>): OutlineSelection {
            val originals = IdentityHashMap<OutlineNode, OutlineNode>()
            return OutlineSelection(outline.flatMap { shown(it, originals) }, originals)
        }

        /**
         * The shown nodes that [node] gives: itself with its shown children if its kind is shown, and otherwise the
         * shown nodes inside it. A copy is recorded in [originals] with the node it stands for.
         */
        private fun shown(node: OutlineNode, originals: IdentityHashMap<OutlineNode, OutlineNode>): List<OutlineNode> {
            val children = node.children.flatMap { shown(it, originals) }
            if (node.kind !in SHOWN) return children
            val same = children.size == node.children.size && children.indices.all { children[it] === node.children[it] }
            return listOf(if (same) node else node.copy(children = children).also { originals[it] = node })
        }
    }
}
