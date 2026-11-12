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
package io.karpfen.ide.ui.workbench.projecttree

import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * Which files of a folder belong to another file of the same folder. The companion file of a diagram, such as
 * `lamp.kstates.vis`, belongs to the model file it is named after, `lamp.kstates`. The project tree shows it below
 * that file and not next to it.
 *
 * A companion file without its model file in the folder belongs to nothing and is shown like any other file.
 */
internal object CompanionFiles {

    /** The file that the file [name] is the companion of, among the [siblings] of its folder, or null. */
    fun ownerOf(name: String, siblings: List<WorkspaceNode>): WorkspaceNode.File? {
        if (FileKind.of(name) != FileKind.LAYOUT) return null
        val model = name.dropLast(FileKind.LAYOUT_SUFFIX.length)
        return siblings.filterIsInstance<WorkspaceNode.File>().firstOrNull { it.name == model }
    }

    /** The companion files of [file] among the [siblings] of its folder, in their order. */
    fun of(file: WorkspaceNode.File, siblings: List<WorkspaceNode>): List<WorkspaceNode.File> =
        siblings.filterIsInstance<WorkspaceNode.File>().filter { ownerOf(it.name, siblings)?.path == file.path }

    /** The path of the file that [path] is the companion of in [tree], or null if it is none or has no owner. */
    fun ownerOf(tree: WorkspaceTree, path: Path): Path? {
        val folder = path.parent?.let(tree::find) as? WorkspaceNode.Directory ?: return null
        return ownerOf(path.fileName.toString(), folder.children)?.path
    }

    /** True if the file [path] of [tree] has a companion file, which makes its row in the project tree expandable. */
    fun hasCompanions(tree: WorkspaceTree, path: Path): Boolean {
        val file = tree.find(path) as? WorkspaceNode.File ?: return false
        val folder = path.parent?.let(tree::find) as? WorkspaceNode.Directory ?: return false
        return of(file, folder.children).isNotEmpty()
    }
}
