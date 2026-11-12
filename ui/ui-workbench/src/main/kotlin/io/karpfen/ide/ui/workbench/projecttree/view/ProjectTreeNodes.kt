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
import io.karpfen.ide.ui.workbench.projecttree.ProjectSection
import java.nio.file.Path

/**
 * The `TreeView` nodes of a [WorkspaceTree], in the three sections of [ProjectSection]. The nodes are keyed by path.
 * Each file gets the icon of its kind from [fileIconOf]. What a node is and what it offers is decided by
 * `ProjectTreeState`.
 *
 * The project file and the environment descriptors come first, one row each. Then comes the node of the folder with
 * everything else below it, in the order of the tree. The bridge files come last, one row each. The first node of the
 * second and of the third section starts its section, so the tree draws a line above it. A section without files is
 * left out, and so is its line. A file of the first or the last section that lies in a subfolder is shown by its path
 * from the root, and it is not listed in its folder.
 *
 * The companion file of a diagram is not listed in its folder. It is the child of the model file it belongs to, as
 * [CompanionFiles] tells, so that file can be expanded like a folder. Opening the model file still opens it, and only
 * the arrow in front of it expands it.
 */
internal class ProjectTreeNodes(tree: WorkspaceTree) {

    private val root: Path = tree.root.path

    /** The nodes of the three sections, from top to bottom. */
    val roots: List<TreeNode<Path>> = run {
        val files = filesOf(tree.root)
        val meta = files.filter { ProjectSection.of(it, root) == ProjectSection.META }.map(::row)
        val bridges = files.filter { ProjectSection.of(it, root) == ProjectSection.BRIDGES }.map(::row)
        meta + node(tree.root).copy(startsSection = meta.isNotEmpty()) + bridges.mapIndexed { index, node -> node.copy(startsSection = index == 0) }
    }

    /** Every file below [directory], each folder's files in the order of the tree. */
    private fun filesOf(directory: WorkspaceNode.Directory): List<WorkspaceNode.File> = directory.children.flatMap { child ->
        when (child) {
            is WorkspaceNode.Directory -> filesOf(child)
            is WorkspaceNode.File -> listOf(child)
        }
    }

    /** The row of [file] in the first or the last section, labelled with its path from the root. */
    private fun row(file: WorkspaceNode.File): TreeNode<Path> =
        TreeNode(file.path, root.relativize(file.path).joinToString("/"), icon = fileIconOf(file.kind))

    private fun node(node: WorkspaceNode.Directory): TreeNode<Path> {
        val children = node.children.filter { child ->
            child !is WorkspaceNode.File ||
                ProjectSection.of(child, root) == ProjectSection.FILES && CompanionFiles.ownerOf(child.name, node.children) == null
        }
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
