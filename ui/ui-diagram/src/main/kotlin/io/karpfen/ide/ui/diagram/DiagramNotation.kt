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
package io.karpfen.ide.ui.diagram

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.notation.diagram.edit.DiagramActions
import io.karpfen.ide.notation.diagram.edit.DiagramCells
import io.karpfen.ide.notation.diagram.edit.StatechartActions
import io.karpfen.ide.notation.diagram.edit.StatechartCells
import io.karpfen.ide.notation.diagram.edit.StructureActions
import io.karpfen.ide.notation.diagram.layout.DiagramArrangement
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.StatechartArrangement
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.StructureArrangement
import io.karpfen.ide.notation.diagram.layout.StructureLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.sync.DiagramProjection

/**
 * Everything the diagram view needs to know about one kind of diagram: how it is derived from the text, how it is
 * laid out, and how it is drawn. The view itself is the same for every kind.
 *
 * @param D the diagram, as it is derived from the text.
 * @param L the layout of that kind of diagram.
 * @property projection derives the diagram from the text.
 * @property arrangement lays the diagram out, automatically and by hand.
 * @property painter draws the diagram.
 * @property nothingToDraw what the view says while the text holds nothing that makes a diagram.
 * @property actions tells what the user can do at a point of the diagram, which the view offers in its context menu,
 *   while the optional language features of KStates it is given are switched on.
 * @property cells tells which pieces of code the diagram shows folded up, to be opened and edited as text.
 */
internal class DiagramNotation<D : Any, L : DiagramLayout>(
    val projection: DiagramProjection<D>,
    val arrangement: DiagramArrangement<D, L>,
    val painter: DiagramPainter<D, L>,
    val nothingToDraw: String,
    val actions: (Set<KstatesFeature>) -> DiagramActions<D, L> = { DiagramActions.none() },
    val cells: DiagramCells<D, L> = DiagramCells.none(),
) {
    companion object {
        /** The statechart of a state machine file. */
        val STATECHART: DiagramNotation<StatechartDiagram, StatechartLayout> = DiagramNotation(
            DiagramProjection.STATECHART,
            StatechartArrangement,
            StatechartPainter,
            "No state machine to draw yet. The diagram appears once the text can be read.",
            ::StatechartActions,
            StatechartCells,
        )

        /** The class diagram of a metamodel file. */
        val TYPES: DiagramNotation<StructureDiagram, StructureLayout> = DiagramNotation(
            DiagramProjection.TYPES,
            StructureArrangement,
            StructurePainter,
            "No types to draw yet. The diagram appears once the text can be read.",
            ::StructureActions,
        )

        /** The object diagram of a model file. */
        val OBJECTS: DiagramNotation<StructureDiagram, StructureLayout> = DiagramNotation(
            DiagramProjection.OBJECTS,
            StructureArrangement,
            StructurePainter,
            "No objects to draw yet. The diagram appears once the text can be read.",
            ::StructureActions,
        )
    }
}
