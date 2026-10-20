package io.karpfen.ide.ui.workbench.projecttree.view

import androidx.compose.ui.graphics.vector.ImageVector
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TreeNode
import java.nio.file.Path

/**
 * The `TreeView` nodes of a [WorkspaceTree] (spec §8): one root node for the folder, children in the tree's order,
 * keyed by path, with file-kind icons. Remembers which keys are directories, so opening a directory toggles it.
 */
internal class ProjectTreeNodes(tree: WorkspaceTree) {

    private val directories = HashSet<Path>()

    /** The root node (the opened folder). */
    val roots: List<TreeNode<Path>> = listOf(node(tree.root))

    /** The workspace root. */
    val root: Path = tree.root.path

    /** True if [path] is a directory node. */
    fun isDirectory(path: Path): Boolean = path in directories

    private fun node(node: WorkspaceNode): TreeNode<Path> = when (node) {
        is WorkspaceNode.Directory -> {
            directories.add(node.path)
            TreeNode(node.path, node.name, node.children.map(::node), KarpfenIcons.Folder)
        }
        is WorkspaceNode.File -> TreeNode(node.path, node.name, icon = iconOf(node.kind))
    }

    companion object {
        /** The icon of a file of [kind]. */
        fun iconOf(kind: FileKind): ImageVector = when (kind) {
            FileKind.KMETA, FileKind.EVENTS_KMETA -> KarpfenIcons.Kmeta
            FileKind.KMODEL -> KarpfenIcons.Kmodel
            FileKind.KSTATES -> KarpfenIcons.Kstates
            FileKind.ENV_DESCRIPTOR, FileKind.LAYOUT -> KarpfenIcons.Json
            FileKind.OTHER -> KarpfenIcons.File
        }
    }
}
