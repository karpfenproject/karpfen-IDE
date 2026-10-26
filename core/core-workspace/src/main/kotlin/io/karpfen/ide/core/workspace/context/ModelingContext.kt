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

import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * A set of files analyzed together: one domain metamodel, an optional event metamodel, models and state machines. All
 * paths are absolute and normalized, and every one is a file of the scanned workspace tree (an explicit context leaves
 * out what its descriptor names but the tree lacks, which the resolver reports); a file may still vanish from disk
 * before the next scan.
 *
 * @property directory the directory the context belongs to.
 * @property descriptor the `*.kenv.json` defining the context, or null for an implicit (directory) context.
 * @property metamodel the domain metamodel, or null if the context has none.
 * @property events the event metamodel (`EVENTS.kmeta` in an implicit context), or null.
 */
public data class ModelingContext(
    public val directory: Path,
    public val descriptor: Path?,
    public val metamodel: Path?,
    public val events: Path?,
    public val models: List<Path>,
    public val stateMachines: List<StateMachineBinding>,
) {
    /** True for a context formed by a directory without descriptor. */
    public val isImplicit: Boolean get() = descriptor == null

    /** Display name: the descriptor name without `.kenv.json`, or the directory name. */
    public val name: String
        get() = descriptor?.fileName?.toString()?.removeSuffix(FileKind.DESCRIPTOR_SUFFIX)
            ?: directory.fileName?.toString()
            ?: directory.toString()

    /** Every karpfen file of the context: metamodel, events, models, state machines (in that order). */
    public val files: List<Path>
        get() = listOfNotNull(metamodel, events) + models + stateMachines.map { it.file }

    /** True if [path] is one of [files]. */
    public operator fun contains(path: Path): Boolean = path in files
}

/**
 * A state machine of a context. [attachedTo] is the object key from the descriptor (`stateMachines[].attachedTo`);
 * null in implicit contexts (the `ATTACHED TO` type in the file still applies).
 */
public data class StateMachineBinding(public val file: Path, public val attachedTo: String?)
