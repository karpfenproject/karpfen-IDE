package io.karpfen.ide.core.workspace.files

import java.nio.file.Path

/**
 * Builds the [WorkspaceTree] of a folder (spec §4): skips hidden directories (name starting with `.`) and ignored
 * directory names; sorts with [WorkspaceNode.ORDER]. Blocking; run on an IO dispatcher. An internal seam of the
 * workspace (injected through `WorkspaceCollaborators`), so coordinator tests can pass a fake tree.
 */
internal interface WorkspaceScanner {

    /** Scans [root] (an existing directory). */
    fun scan(root: Path): WorkspaceTree

    /** True if [path] lies in an ignored or hidden directory below [root]. */
    fun isIgnored(root: Path, path: Path): Boolean
}

/** The [WorkspaceScanner] walking the file system, ignoring hidden directories and [ignoredDirectoryNames]. */
internal fun WorkspaceScanner(ignoredDirectoryNames: Set<String>): WorkspaceScanner =
    FileSystemWorkspaceScanner(ignoredDirectoryNames)

internal class FileSystemWorkspaceScanner(private val ignoredDirectoryNames: Set<String>) : WorkspaceScanner {

    override fun scan(root: Path): WorkspaceTree = TODO("WP-WORKSPACE: walk $root")

    override fun isIgnored(root: Path, path: Path): Boolean = TODO("WP-WORKSPACE: ignore rules")
}
