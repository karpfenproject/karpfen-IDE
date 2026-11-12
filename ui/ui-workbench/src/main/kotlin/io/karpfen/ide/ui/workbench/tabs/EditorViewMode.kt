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
package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.ui.diagram.DiagramKind

/**
 * How the editor area shows a file that has a diagram. The mode belongs to one file. The diagram is always the one of
 * the file itself, next to its own text or in its place.
 *
 * @property label the name of the mode on its button.
 */
public enum class EditorViewMode(public val label: String) {
    /** The text alone. */
    TEXT("Text"),

    /** The text and the diagram next to each other. */
    SPLIT("Split"),

    /** The diagram alone. */
    DIAGRAM("Diagram"),
    ;

    public companion object {
        /**
         * The diagram that files of [kind] have, or null if they have none. A state machine has a statechart, a
         * metamodel a class diagram, and a model an object diagram.
         */
        public fun diagramOf(kind: FileKind): DiagramKind? = when (kind) {
            FileKind.KSTATES -> DiagramKind.STATECHART
            FileKind.KMETA, FileKind.EVENTS_KMETA -> DiagramKind.TYPES
            FileKind.KMODEL -> DiagramKind.OBJECTS
            else -> null
        }

        /** True if files of [kind] have a diagram. */
        public fun hasDiagram(kind: FileKind): Boolean = diagramOf(kind) != null
    }
}
