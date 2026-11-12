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
package io.karpfen.ide.notation.sync

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import io.karpfen.ide.notation.diagram.structure.MetamodelProjection
import io.karpfen.ide.notation.diagram.structure.ModelProjection
import io.karpfen.ide.notation.diagram.structure.StructureDiagram

/**
 * Derives a diagram from the text of a model file and its outline. Every language with a diagram has one projection.
 *
 * @param D the diagram, as it is derived from the text.
 */
public fun interface DiagramProjection<out D : Any> {
    /**
     * The diagram of [text], whose outline is [outline], or null if the text holds nothing that makes a diagram.
     * [outline] must be the outline of [text].
     */
    public fun of(text: String, outline: List<OutlineNode>): D?

    public companion object {
        /** The statechart of a `.kstates` file. */
        public val STATECHART: DiagramProjection<StatechartDiagram> = DiagramProjection(StatechartProjection::of)

        /** The class diagram of a `.kmeta` file. */
        public val TYPES: DiagramProjection<StructureDiagram> = DiagramProjection { _, outline -> MetamodelProjection.of(outline) }

        /** The object diagram of a `.kmodel` file. */
        public val OBJECTS: DiagramProjection<StructureDiagram> = DiagramProjection { _, outline -> ModelProjection.of(outline) }
    }
}
