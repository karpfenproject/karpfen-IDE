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
package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.workspace.diagnostics.DiagnosticsByFile
import io.karpfen.ide.core.workspace.diagnostics.UnreadableFindings
import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * Implements the rules described on [ContextResolver]. The explicit contexts come from [DescriptorContexts]. The
 * implicit ones come from [DirectoryContexts], over the files that no explicit context contains. The loose files are
 * every karpfen source of the tree that is in no context. Each directory the scan could not list gets an `unreadable`
 * ERROR.
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

    /** [directory] and every directory below it that the scan could not list. */
    private fun unreadableDirectories(directory: WorkspaceNode.Directory): List<Path> =
        listOfNotNull(directory.path.takeUnless { directory.isReadable }) +
            directory.children.filterIsInstance<WorkspaceNode.Directory>().flatMap(::unreadableDirectories)
}
