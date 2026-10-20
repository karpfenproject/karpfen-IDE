package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * Derives the [ModelingContext]s of a workspace (spec §4). Pure: works on the scanned tree and the already-read
 * descriptors, so it is unit-testable without a file system.
 *
 * Rules (MUST):
 * - Every successfully read descriptor defines one explicit context; its paths are resolved against the descriptor's
 *   directory; a referenced file missing from [WorkspaceTree] is an ERROR `descriptor-missing-file` on the descriptor
 *   at the reference's range. A descriptor that could not be read defines no context.
 * - Every directory that contains karpfen files and no descriptor forms an implicit context: metamodel = the single
 *   `.kmeta` that is not `EVENTS.kmeta` (several → INFO `context-multiple-metamodels` on each, the alphabetically first
 *   is used), events = `EVENTS.kmeta`, models = all `.kmodel`, state machines = all `.kstates` (attachedTo null).
 *   Directories containing a descriptor are covered by it; their files not named by any descriptor are not analyzed.
 * - The descriptors' own read diagnostics are included in [ContextResolution.diagnostics].
 */
public fun interface ContextResolver {
    /** Resolves the contexts of [tree]; [descriptors] holds the read result of every `*.kenv.json` in the tree. */
    public fun resolve(tree: WorkspaceTree, descriptors: Map<Path, DescriptorReadResult>): ContextResolution
}

/** The default [ContextResolver]. */
public fun ContextResolver(): ContextResolver = DefaultContextResolver()
