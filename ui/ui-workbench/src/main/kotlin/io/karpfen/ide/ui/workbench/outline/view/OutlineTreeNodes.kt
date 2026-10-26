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
package io.karpfen.ide.ui.workbench.outline.view

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.ui.foundation.widgets.TreeNode

/** The key of an outline node in the `TreeView`: its index path from the top level (unique, stable while the tree is). */
internal data class OutlineKey(val indices: List<Int>)

/**
 * The `TreeView` nodes of an outline: labels `name : detail`, keyed by index path, and the lookups between keys and
 * [OutlineNode]s the view needs (navigation by key, the highlighted node's key).
 */
internal class OutlineTreeNodes(private val nodes: List<OutlineNode>) {

    /** The top-level tree nodes. */
    val roots: List<TreeNode<OutlineKey>> = nodes.mapIndexed { index, node -> treeNode(node, listOf(index)) }

    /** Every key with children (expandable). */
    val expandable: Set<OutlineKey> = buildSet { collectExpandable(nodes, emptyList(), this) }

    /** The outline node of [key], or null. */
    fun nodeOf(key: OutlineKey): OutlineNode? {
        var level = nodes
        var node: OutlineNode? = null
        for (index in key.indices) {
            node = level.getOrNull(index) ?: return null
            level = node.children
        }
        return node
    }

    /** The key of the innermost node of the [chain] (outermost first, as `OutlineUiState.highlighted`), or null. */
    fun keyOf(chain: List<OutlineNode>): OutlineKey? {
        if (chain.isEmpty()) return null
        val indices = ArrayList<Int>()
        var level = nodes
        for (node in chain) {
            val index = level.indexOfFirst { it === node }.takeIf { it >= 0 } ?: level.indexOf(node).takeIf { it >= 0 } ?: return null
            indices += index
            level = node.children
        }
        return OutlineKey(indices)
    }

    private fun treeNode(node: OutlineNode, indices: List<Int>): TreeNode<OutlineKey> = TreeNode(
        key = OutlineKey(indices),
        label = if (node.detail.isNullOrBlank()) node.name else "${node.name} : ${node.detail}",
        children = node.children.takeIf { it.isNotEmpty() }?.mapIndexed { index, child -> treeNode(child, indices + index) },
    )

    private fun collectExpandable(level: List<OutlineNode>, prefix: List<Int>, into: MutableSet<OutlineKey>) {
        level.forEachIndexed { index, node ->
            if (node.children.isNotEmpty()) {
                into += OutlineKey(prefix + index)
                collectExpandable(node.children, prefix + index, into)
            }
        }
    }
}
