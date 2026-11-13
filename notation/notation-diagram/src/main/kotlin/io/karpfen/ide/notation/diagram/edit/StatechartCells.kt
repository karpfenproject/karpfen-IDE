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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartMetrics
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TransitionEdge
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The code cells of a statechart: the tick limit, the entry actions and the do actions of every state that has them,
 * and the condition of every transition. A tick limit whose code is deleted is taken out of its state. A transition without a condition has a cell too. It is empty, and code written into
 * it becomes the condition. A condition whose code is deleted is taken out of the transition, because the language
 * knows no empty condition.
 */
public object StatechartCells : DiagramCells<StatechartDiagram, StatechartLayout> {

    private const val CONDITION = "CONDITION"

    override fun of(source: String, diagram: StatechartDiagram, layout: StatechartLayout): List<DiagramCell> {
        val ofStates = diagram.allStates().flatMap { state ->
            val box = layout.states[state.name] ?: return@flatMap emptyList()
            val places = StatechartMetrics.cellBoxes(state, box)
            listOfNotNull(
                state.tickLimitRange?.let { Triple(StatechartMetrics.LIMIT_WORD, "Tick limit of ${state.name}", it) },
                state.entryRange?.let { Triple(StatechartMetrics.ENTRY_WORD, "Entry actions of ${state.name}", it) },
                state.doRange?.let { Triple(StatechartMetrics.DO_WORD, "Do actions of ${state.name}", it) },
            ).mapNotNull { (word, title, range) ->
                val block = TextBlocks.blockFrom(source, range.start) ?: return@mapNotNull null
                val place = places[word] ?: return@mapNotNull null
                DiagramCell("${state.name}.$word", title, word, place, TextBlocks.inner(source, block)) { code ->
                    if (word == StatechartMetrics.LIMIT_WORD && code.isBlank()) {
                        DiagramChange(listOf(TextBlocks.removal(source, range)))
                    } else {
                        replaced(source, block, TextBlocks.braced(code, TextBlocks.indentOf(source, block.start)))
                    }
                }
            }
        }
        val transitions = diagram.transitions.associateBy { it.id }
        val ofTransitions = layout.routes.mapNotNull { route ->
            val label = route.label ?: return@mapNotNull null
            val transition = transitions[route.transitionId] ?: return@mapNotNull null
            condition(source, transition, StatechartMetrics.cellBox(label))
        }
        return ofStates + ofTransitions
    }

    /** The cell of the condition of [transition], with its marker at [place]. */
    private fun condition(source: String, transition: TransitionEdge, place: DiagramBox): DiagramCell? {
        val body = TextBlocks.blockFrom(source, transition.range.start) ?: return null
        val block = transition.conditionRange?.let { TextBlocks.blockFrom(source, it.start) }
        val indent = TextBlocks.indentOf(source, transition.range.start)
        val title = "Condition of ${transition.sources.joinToString(", ")} to ${transition.targets.joinToString(", ")}"
        return DiagramCell(transition.id, title, "", place, block?.let { TextBlocks.inner(source, it) }.orEmpty()) { code ->
            val inner = TextBlocks.deeper(indent)
            when {
                code.isBlank() && block == null -> null
                code.isBlank() -> replaced(source, body, "{ }")
                block != null -> replaced(source, block, TextBlocks.braced(code, TextBlocks.indentOf(source, block.start)))
                else -> replaced(source, body, "{\n$inner$CONDITION ${TextBlocks.braced(code, inner)}\n$indent}")
            }
        }
    }

    /** The change that puts [text] in the place of [range] of [source], or null if it stands there already. */
    private fun replaced(source: String, range: TextRange, text: String): DiagramChange? =
        if (source.substring(range.start, range.end) == text) null else DiagramChange(listOf(TextEdit(range, text)))
}
