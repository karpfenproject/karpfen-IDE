package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.UnreadableFindings
import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * Implements the rules documented on [ContextResolver]: explicit contexts from [DescriptorContexts], implicit ones from
 * [DirectoryContexts] over the files no explicit context contains, the loose files — every karpfen source of the
 * tree that is in no context (decision 29) — and an `unreadable` ERROR on each directory the scan could not list.
 */
internal class DefaultContextResolver : ContextResolver {

    override fun resolve(tree: WorkspaceTree, descriptors: Map<Path, DescriptorReadResult>): ContextResolution {
        val diagnostics = DiagnosticsByFile()
        val explicit = DescriptorContexts(tree, descriptors).resolve(diagnostics)
        val covered = explicit.flatMapTo(HashSet()) { it.files }
        val contexts = explicit + DirectoryContexts(tree, covered).resolve(diagnostics)
        val inContext = contexts.flatMapTo(HashSet()) { it.files }
        val looseFiles = tree.files().filter { it.kind.isKarpfenSource && it.path !in inContext }.map { it.path }
        unreadableDirectories(tree.root).forEach { diagnostics.add(it, UnreadableFindings.directory()) }
        return ContextResolution(contexts, diagnostics.build(), looseFiles)
    }

    private fun unreadableDirectories(directory: WorkspaceNode.Directory): List<Path> =
        listOfNotNull(directory.path.takeUnless { directory.isReadable }) +
            directory.children.filterIsInstance<WorkspaceNode.Directory>().flatMap(::unreadableDirectories)
}
