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
package io.karpfen.ide.ui.foundation.widgets

/**
 * A visible row of a [TreeView]: [node] at nesting [depth], which is 0 for roots. [parentIndex] is the index of the
 * parent's row in the same row list, or -1 for a root.
 */
internal data class TreeRow<K>(val node: TreeNode<K>, val depth: Int, val parentIndex: Int) {
    /** Whether the node can be expanded, which is the case when it has a children list, even an empty one. */
    val isExpandable: Boolean get() = node.children != null
}

/** The rows a [TreeView] shows: the nodes whose ancestors are all expanded, in pre-order. The computation is pure. */
internal object TreeRows {

    /**
     * The visible rows of [roots] when the nodes with the keys in [expanded] are expanded. The pre-order traversal is
     * iterative and keeps the pending rows on an explicit stack, so deep trees cannot overflow the call stack.
     */
    fun <K> flatten(roots: List<TreeNode<K>>, expanded: Set<K>): List<TreeRow<K>> {
        val rows = ArrayList<TreeRow<K>>()
        val pending = ArrayDeque<TreeRow<K>>()
        roots.asReversed().forEach { pending.addFirst(TreeRow(it, depth = 0, parentIndex = -1)) }
        while (pending.isNotEmpty()) {
            val row = pending.removeFirst()
            val index = rows.size
            rows += row
            val children = row.node.children
            if (children != null && row.node.key in expanded) {
                children.asReversed().forEach { child -> pending.addFirst(TreeRow(child, row.depth + 1, index)) }
            }
        }
        return rows
    }
}
