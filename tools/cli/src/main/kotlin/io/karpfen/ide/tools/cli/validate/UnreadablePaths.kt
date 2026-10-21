package io.karpfen.ide.tools.cli.validate

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Code of the report's ERROR for a karpfen file or a directory `karpfen validate` could not read. */
internal const val UNREADABLE_CODE: String = "unreadable"

/** A karpfen source ([isDirectory] false) or a directory of the scanned tree that cannot be read. */
internal data class UnreadablePath(val path: Path, val isDirectory: Boolean)

/**
 * The karpfen sources and directories of the scanned [tree] that cannot be read (blocking). The workspace skips them —
 * its scan lists a directory it cannot read as empty, its analysis leaves a file it cannot read out — so its problems
 * alone would let `karpfen validate` pass a tree it never saw. Other files are not validated anyway, and an unreadable
 * environment descriptor is the workspace's own `descriptor-invalid` ERROR.
 */
internal fun unreadablePathsOf(tree: WorkspaceTree): List<UnreadablePath> {
    val result = ArrayList<UnreadablePath>()
    fun visit(node: WorkspaceNode) {
        when (node) {
            is WorkspaceNode.Directory -> {
                if (!canList(node.path)) result += UnreadablePath(node.path, isDirectory = true)
                node.children.forEach(::visit)
            }
            is WorkspaceNode.File ->
                if (node.kind.isKarpfenSource && !canRead(node.path)) result += UnreadablePath(node.path, isDirectory = false)
        }
    }
    visit(tree.root)
    return result
}

/** The report's ERROR for this path at 1:1, with the path relative to [root] (`.` for the root itself). */
internal fun UnreadablePath.toReportedProblem(root: Path): ReportedProblem = ReportedProblem(
    path = reportPath(root, path).ifEmpty { "." },
    line = 1,
    column = 1,
    severity = Severity.ERROR,
    message = if (isDirectory) {
        "Cannot read this directory, so its files were not validated."
    } else {
        "Cannot read this file, so it was not validated."
    },
    code = UNREADABLE_CODE,
)

private fun canList(directory: Path): Boolean = attempt { Files.newDirectoryStream(directory).close() }

private fun canRead(file: Path): Boolean = attempt { Files.newByteChannel(file).close() }

private inline fun attempt(access: () -> Unit): Boolean =
    try {
        access()
        true
    } catch (e: IOException) {
        false
    }
