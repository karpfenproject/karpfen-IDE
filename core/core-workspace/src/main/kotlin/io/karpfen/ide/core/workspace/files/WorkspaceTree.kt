package io.karpfen.ide.core.workspace.files

import java.nio.file.Path

/**
 * The files of a workspace as a tree (spec §4): ignored and hidden directories are absent; every directory lists
 * its sub-directories first, then its files, each in case-insensitive name order ([WorkspaceNode.ORDER]).
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
        /** A tree with an empty root directory at [root] (before the first scan). */
        public fun empty(root: Path): WorkspaceTree =
            WorkspaceTree(WorkspaceNode.Directory(root, root.fileName?.toString() ?: root.toString(), emptyList()))
    }
}

/** A node of a [WorkspaceTree]. Paths are absolute and normalized. */
public sealed interface WorkspaceNode {

    /** Absolute, normalized path. */
    public val path: Path

    /** File or directory name (the last path segment). */
    public val name: String

    /** A directory with its visible [children], sorted with [ORDER] (checked). */
    public data class Directory(
        override val path: Path,
        override val name: String,
        public val children: List<WorkspaceNode>,
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
        /** Directories before files; then case-insensitive by name; then case-sensitive as tie-breaker. */
        public val ORDER: Comparator<WorkspaceNode> =
            compareBy<WorkspaceNode> { if (it is Directory) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }
    }
}
