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
package io.karpfen.ide.core.workspace.files

import java.nio.file.Path

/**
 * The files of a workspace as a tree. Ignored and hidden directories are absent. Every directory lists its
 * subdirectories first and then its files, each group in case-insensitive name order as defined by
 * [WorkspaceNode.ORDER].
 */
public data class WorkspaceTree(public val root: WorkspaceNode.Directory) {

    /** Every file in the tree, depth-first in display order. */
    public fun files(): List<WorkspaceNode.File> {
        val result = ArrayList<WorkspaceNode.File>()
        fun visit(directory: WorkspaceNode.Directory) {
            for (child in directory.children) {
                when (child) {
                    is WorkspaceNode.Directory -> visit(child)
                    is WorkspaceNode.File -> result += child
                }
            }
        }
        visit(root)
        return result
    }

    /** Every file of [kind], depth-first in display order. */
    public fun files(kind: FileKind): List<WorkspaceNode.File> = files().filter { it.kind == kind }

    /** The node at [path], or null if the tree does not contain it. */
    public fun find(path: Path): WorkspaceNode? {
        if (path == root.path) return root
        if (!path.startsWith(root.path)) return null
        var current: WorkspaceNode = root
        for (segment in root.path.relativize(path)) {
            val directory = current as? WorkspaceNode.Directory ?: return null
            current = directory.children.firstOrNull { it.name == segment.toString() } ?: return null
        }
        return current
    }

    public companion object {
        /** A tree with an empty root directory at [root]. The workspace shows it before the first scan. */
        public fun empty(root: Path): WorkspaceTree =
            WorkspaceTree(WorkspaceNode.Directory(root, root.fileName?.toString() ?: root.toString(), emptyList()))
    }
}

/** A node of a [WorkspaceTree]. Paths are absolute and normalized. */
public sealed interface WorkspaceNode {

    /** The absolute, normalized path. */
    public val path: Path

    /** The file or directory name, which is the last path segment. */
    public val name: String

    /**
     * A directory with its visible [children]. They must be sorted with [ORDER], and the constructor checks that.
     *
     * @property isReadable false if the directory could not be listed, for example because of permissions. Then its
     *   files are unknown and [children] is empty. The workspace reports it as an ERROR `unreadable`.
     */
    public data class Directory(
        override val path: Path,
        override val name: String,
        public val children: List<WorkspaceNode>,
        public val isReadable: Boolean = true,
    ) : WorkspaceNode {
        init {
            for (i in 1 until children.size) {
                require(ORDER.compare(children[i - 1], children[i]) <= 0) {
                    "children of $path must be sorted: ${children[i - 1].name} before ${children[i].name}"
                }
            }
        }
    }

    /** A file and its [kind]. */
    public data class File(
        override val path: Path,
        override val name: String,
        public val kind: FileKind,
    ) : WorkspaceNode

    public companion object {
        /**
         * The display order. Directories come before files. Within each group the names are compared ignoring case, and
         * names that are equal then are compared case-sensitively.
         */
        public val ORDER: Comparator<WorkspaceNode> =
            compareBy<WorkspaceNode> { if (it is Directory) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }
    }
}
