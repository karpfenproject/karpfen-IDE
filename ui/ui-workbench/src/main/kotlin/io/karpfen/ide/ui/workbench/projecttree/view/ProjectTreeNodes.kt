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
import java.nio.file.Path

/**
 * The `TreeView` nodes of a [WorkspaceTree]: one root node for the folder, children in the tree's order, keyed by path,
 * with the icon of each file's kind ([fileIconOf]). What a node is and offers is the `ProjectTreeState`'s.
 */
internal class ProjectTreeNodes(tree: WorkspaceTree) {

    /** The root node (the opened folder). */
    val roots: List<TreeNode<Path>> = listOf(node(tree.root))

    private fun node(node: WorkspaceNode): TreeNode<Path> = when (node) {
        is WorkspaceNode.Directory -> TreeNode(node.path, node.name, node.children.map(::node), KarpfenIcons.Folder)
        is WorkspaceNode.File -> TreeNode(node.path, node.name, icon = fileIconOf(node.kind))
    }
}
