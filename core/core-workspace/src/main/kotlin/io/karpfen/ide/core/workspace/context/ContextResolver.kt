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

import io.karpfen.ide.core.workspace.environment.DescriptorReadResult
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * Derives the [ModelingContext]s of a workspace. It is pure. It works on the scanned tree and the descriptors that
 * were already read, so it can be unit-tested without a file system.
 *
 * Every implementation follows these rules.
 * - Every descriptor that was read successfully defines one explicit context. Its paths are resolved against the
 *   directory of the descriptor. A referenced file that is missing from the [WorkspaceTree] is an ERROR
 *   `descriptor-missing-file` on the descriptor at the range of the reference, and it is not part of the context. A
 *   referenced file in another language than its role needs is an ERROR `descriptor-invalid` there. The metamodel and
 *   the events need KMeta, a model needs KModel, and a state machine needs KStates. A bridge may be any file. Both
 *   checks follow `DescriptorFiles`, the same rule the descriptor features of the editor follow. A descriptor that
 *   could not be read defines no context.
 * - Every directory without a descriptor forms an implicit context of those of its karpfen files that no explicit
 *   context contains. If no such file is left, there is no implicit context. The metamodel is the single `.kmeta` file
 *   that is not `EVENTS.kmeta`. If there are several, each of them gets an INFO `context-multiple-metamodels`, the
 *   alphabetically first one is used and the others become loose files. The events file is `EVENTS.kmeta`. The models
 *   are all `.kmodel` files, and the state machines are all `.kstates` files, each with a null `attachedTo`. A
 *   directory that contains a descriptor is covered by it. Its karpfen files that no descriptor names are loose files.
 * - [ContextResolution.looseFiles] is exactly the set of karpfen sources of the tree that are in the files of no
 *   context. They are tokenized and parsed, and the project analysis reads a loose state machine with dsl-tools on
 *   its own.
 * - The findings of the descriptors, from [DescriptorReadResult.diagnostics], are part of
 *   [ContextResolution.diagnostics]. Their syntax errors are not, because those belong to the text of the descriptor.
 * - A directory of the tree that could not be listed, where `WorkspaceNode.Directory.isReadable` is false, is an ERROR
 *   `unreadable` on the directory. The files in it are unknown, so they are in no context.
 */
public fun interface ContextResolver {
    /** Resolves the contexts of [tree]. [descriptors] holds the read result of every `*.kenv.json` in the tree. */
    public fun resolve(tree: WorkspaceTree, descriptors: Map<Path, DescriptorReadResult>): ContextResolution
}

/** The default [ContextResolver]. */
public fun ContextResolver(): ContextResolver = DefaultContextResolver()
