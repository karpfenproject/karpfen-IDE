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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path

/** What an uploaded text is to the runtime, which decides the route it goes to. */
public enum class UploadRole {
    /**
     * The domain metamodel. It is uploaded first, because the event definitions and the model are read against its
     * types.
     */
    METAMODEL,

    /** The event definitions, read with the domain types as base types. */
    EVENTS,

    /** The model the runtime starts from. */
    MODEL,

    /** A state machine, attached to one object key. */
    STATE_MACHINE,
}

/**
 * One text a deployment uploads. Immutable.
 *
 * @property role what the text is to the runtime.
 * @property file the file the text was analyzed from.
 * @property snapshot the analyzed text, with line separators normalized to `\n` and without a byte order mark. After
 *   the preparation saved every open document, it is the saved text.
 * @property attachedTo the object key a state machine is attached to. It is null for every other role.
 */
public data class PlannedUpload(
    public val role: UploadRole,
    public val file: Path,
    public val snapshot: TextSnapshot,
    public val attachedTo: String?,
) {
    init {
        require((role == UploadRole.STATE_MACHINE) == (attachedTo != null)) {
            "exactly the state machine uploads name the key they are attached to: $role of $file attached to $attachedTo"
        }
    }

    /** The file name, as titles and messages show it. */
    public val fileName: String
        get() = file.fileName?.toString() ?: file.toString()
}

/**
 * A state machine of the plan and what it is attached to. Immutable.
 *
 * @property attachedTo the key of the model object the machine runs attached to.
 * @property file the machine's file.
 * @property objectType the type of the object with that key in the runtime's index of the model. For equal keys it is
 *   the type of the last object. It is null if the model has no such object.
 * @property machineType the type the machine names after `ATTACHED TO`, which the runtime never reads.
 */
public data class AttachedMachine(
    public val attachedTo: String,
    public val file: Path,
    public val objectType: String?,
    public val machineType: String,
)
