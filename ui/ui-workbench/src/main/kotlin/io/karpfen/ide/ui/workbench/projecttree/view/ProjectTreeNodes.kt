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
package io.karpfen.ide.ui.workbench.projecttree.view

import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TreeNode
import io.karpfen.ide.ui.workbench.files.view.fileIconOf
import io.karpfen.ide.ui.workbench.projecttree.CompanionFiles
import java.nio.file.Path

/**
 * The `TreeView` nodes of a [WorkspaceTree]. There is one root node for the folder. The children keep the order of the
 * tree and are keyed by path. Each file gets the icon of its kind from [fileIconOf]. What a node is and what it offers
 * is decided by `ProjectTreeState`.
 *
 * The companion file of a diagram is not listed in its folder. It is the child of the model file it belongs to, as
 * [CompanionFiles] tells, so that file can be expanded like a folder. Opening the model file still opens it, and only
 * the arrow in front of it expands it.
 */
internal class ProjectTreeNodes(tree: WorkspaceTree) {

    /** The single root node, which is the opened folder. */
    val roots: List<TreeNode<Path>> = listOf(node(tree.root))

    private fun node(node: WorkspaceNode.Directory): TreeNode<Path> {
        val children = node.children.filter { it !is WorkspaceNode.File || CompanionFiles.ownerOf(it.name, node.children) == null }
        return TreeNode(node.path, node.name, children.map { child(it, node.children) }, KarpfenIcons.Folder)
    }

    /** The node of [node], one of the [siblings] of a folder. A file with companion files gets them as its children. */
    private fun child(node: WorkspaceNode, siblings: List<WorkspaceNode>): TreeNode<Path> = when (node) {
        is WorkspaceNode.Directory -> node(node)
        is WorkspaceNode.File -> {
            val companions = CompanionFiles.of(node, siblings).map { TreeNode(it.path, it.name, icon = fileIconOf(it.kind)) }
            TreeNode(node.path, node.name, companions.takeIf { it.isNotEmpty() }, fileIconOf(node.kind))
        }
    }
}
