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

import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * A set of files analyzed together: one domain metamodel, an optional event metamodel, models and state machines. All
 * paths are absolute and normalized. Every one of them is a file of the scanned workspace tree, because an explicit
 * context leaves out what its descriptor names but the tree lacks, and the resolver reports that. A file may still
 * vanish from disk before the next scan.
 *
 * An explicit context also carries the typed descriptor it was resolved from. So everything else the descriptor says,
 * such as the tick delay and the event time to live of a deployment, has one source: the text of the descriptor as
 * the workspace read it. The descriptor is part of the equality of the context. So editing only such a value yields a
 * new resolution and a new analysis. That analysis reads no file again, because the analyzer remembers its reads by
 * text.
 *
 * @property directory the directory the context belongs to.
 * @property descriptor the `*.kenv.json` that defines the context, or null for an implicit context of a directory.
 * @property metamodel the domain metamodel, or null if the context has none.
 * @property events the event metamodel, or null. In an implicit context it is `EVENTS.kmeta`.
 * @property models the models of the context. An explicit context has at most one.
 * @property stateMachines the state machines of the context. In an explicit context they carry their attachments.
 * @property environment the typed descriptor the context was resolved from, decoded from the same text as
 *   [descriptor]. It is null for an implicit context.
 */
public data class ModelingContext(
    public val directory: Path,
    public val descriptor: Path?,
    public val metamodel: Path?,
    public val events: Path?,
    public val models: List<Path>,
    public val stateMachines: List<StateMachineBinding>,
    public val environment: EnvironmentDescriptor? = null,
) {
    /** True for a context formed by a directory without a descriptor. */
    public val isImplicit: Boolean get() = descriptor == null

    /** The display name. It is the descriptor name without `.kenv.json`, or else the directory name. */
    public val name: String
        get() = descriptor?.fileName?.toString()?.removeSuffix(FileKind.DESCRIPTOR_SUFFIX)
            ?: directory.fileName?.toString()
            ?: directory.toString()

    /** Every karpfen file of the context, in this order: metamodel, events, models and state machines. */
    public val files: List<Path>
        get() = listOfNotNull(metamodel, events) + models + stateMachines.map { it.file }

    /** True if [path] is one of [files]. */
    public operator fun contains(path: Path): Boolean = path in files
}

/**
 * A state machine of a context. [attachedTo] is the object key from the descriptor, `stateMachines[].attachedTo`. It
 * is null in implicit contexts, where the `ATTACHED TO` type in the file still applies.
 */
public data class StateMachineBinding(public val file: Path, public val attachedTo: String?)
