package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/** Implements the rules documented on [ContextResolver]. */
internal class DefaultContextResolver : ContextResolver {
    override fun resolve(tree: WorkspaceTree, descriptors: Map<Path, DescriptorReadResult>): ContextResolution =
        TODO("WP-WORKSPACE: explicit and implicit modeling contexts")
}
