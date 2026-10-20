package io.karpfen.ide.core.workspace.files

import java.nio.file.Path

/**
 * Builds a [WorkspaceTree] by walking the file system (spec §4): skips hidden directories (name starting with `.`)
 * and directories named in [ignoredDirectoryNames]; sorts with [WorkspaceNode.ORDER]. Blocking; run on an IO
 * dispatcher.
 */
internal class WorkspaceScanner(private val ignoredDirectoryNames: Set<String>) {

    /** Scans [root] (an existing directory). */
    fun scan(root: Path): WorkspaceTree = TODO("WP-WORKSPACE: walk $root")

    /** True if [path] lies in an ignored or hidden directory below [root]. */
    fun isIgnored(root: Path, path: Path): Boolean = TODO("WP-WORKSPACE: ignore rules")
}
