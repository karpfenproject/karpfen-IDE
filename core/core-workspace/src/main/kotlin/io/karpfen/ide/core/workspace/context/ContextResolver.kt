package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * Derives the [ModelingContext]s of a workspace. Pure: works on the scanned tree and the already-read
 * descriptors, so it is unit-testable without a file system.
 *
 * Rules (MUST):
 * - Every successfully read descriptor defines one explicit context; its paths are resolved against the descriptor's
 *   directory; a referenced file missing from [WorkspaceTree] is an ERROR `descriptor-missing-file` on the descriptor
 *   at the reference's range, one in another language than its role (metamodel and events KMeta, model KModel, state
 *   machines KStates; bridges any) an ERROR `descriptor-invalid` there. A descriptor that could not be read defines no
 *   context.
 * - Every directory that contains no descriptor forms an implicit context of its karpfen files that no explicit
 *   context contains (none left → no implicit context): metamodel = the single `.kmeta` that is not `EVENTS.kmeta`
 *   (several → INFO `context-multiple-metamodels` on each, the alphabetically first is used, the others are loose
 *   files), events = `EVENTS.kmeta`, models = all `.kmodel`, state machines = all `.kstates` (attachedTo null).
 *   Directories containing a descriptor are covered by it; their karpfen files not named by any descriptor are loose
 *   files.
 * - [ContextResolution.looseFiles] is exactly the set of karpfen sources of the tree that are in no context's files
 *   (not converted, but still tokenized and parsed; the same rule in the IDE and the CLI).
 * - The descriptors' own read diagnostics are included in [ContextResolution.diagnostics].
 * - A directory of the tree that could not be listed (`WorkspaceNode.Directory.isReadable` false) is an ERROR
 *   `unreadable` on the directory: the files in it are unknown, so they are in no context.
 */
public fun interface ContextResolver {
    /** Resolves the contexts of [tree]; [descriptors] holds the read result of every `*.kenv.json` in the tree. */
    public fun resolve(tree: WorkspaceTree, descriptors: Map<Path, DescriptorReadResult>): ContextResolution
}

/** The default [ContextResolver]. */
public fun ContextResolver(): ContextResolver = DefaultContextResolver()
