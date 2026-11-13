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
package io.karpfen.ide.notation.diagram.layout

import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartEdge
import io.karpfen.ide.notation.diagram.statechart.TransitionEdge

/**
 * The measures of a drawn statechart, in the diagram's own units. The automatic layout and the layout by hand share
 * them, so a state has the same size in both.
 */
public object StatechartMetrics {
    /** The room at the top of the box of a state with nested states, which holds its name. */
    public const val HEADER: Float = 26f

    /** The height of a state without nested states. */
    public const val STATE_HEIGHT: Float = 36f

    /** The extra height of a state that has a tick limit, an entry or a do block, for the line that says so. */
    public const val BLOCK_LINE: Float = 16f

    /** The smallest width of a state. */
    public const val MIN_STATE_WIDTH: Float = 80f

    /** The room left and right of a state's name. */
    public const val NAME_PADDING: Float = 14f

    /** The room around the nested states inside their parent. */
    public const val INNER_MARGIN: Float = 16f

    /** The room around the whole drawing. */
    public const val MARGIN: Float = 24f

    /** The diameter of the marker of an initial state. */
    public const val MARKER: Float = 14f

    /** The distance between the marker of an initial state and that state, where the layout is made by hand. */
    public const val MARKER_GAP: Float = 16f

    /** The length of the bar of a split or join. */
    public const val BAR_LENGTH: Float = 60f

    /** The thickness of the bar of a split or join. */
    public const val BAR_THICKNESS: Float = 6f

    /** The height of the label of a transition. */
    public const val LABEL_HEIGHT: Float = 16f

    /** The room a label gets beyond the width of its text. */
    public const val LABEL_PADDING: Float = 8f

    /**
     * The distance between a timeout and a normal transition that connect the same two states, where the layout is
     * made by hand and neither has waypoints.
     */
    public const val SPREAD: Float = 20f

    /** The distance between two elements that are placed next to each other automatically. */
    public const val GAP: Float = 40f

    /** The side of the marker of a code cell, the small circle with a plus. */
    public const val CELL_MARK: Float = 12f

    /** The room between the marker of a code cell and the word behind it, and between two cells. */
    public const val CELL_GAP: Float = 4f

    /** The room a label gets for the marker of the code cell of its transition. */
    public const val CELL_ROOM: Float = CELL_MARK + CELL_GAP

    /** The width that a character of the word behind a marker is taken to have. */
    private const val CELL_CHARACTER: Float = 6.5f

    /** The word behind the marker of the entry block of a state. */
    public const val ENTRY_WORD: String = "entry"

    /** The word behind the marker of the do block of a state. */
    public const val DO_WORD: String = "do"

    /** The word behind the marker of the tick limit of a state. */
    public const val LIMIT_WORD: String = "limit"

    /** The diameter of the badge of a history, the circle with an H in it. */
    public const val BADGE: Float = 16f

    /** The radius of the dot of a breakpoint. */
    public const val BREAKPOINT_DOT: Float = 4f

    /** The width of the box of [state] if it has no nested states, with a name as wide as [text] says. */
    public fun leafWidth(state: StateNode, text: TextMeasurer): Float =
        maxOf(text.widthOf(state.name) + 2 * NAME_PADDING, MIN_STATE_WIDTH, cellsWidth(state) + 2 * CELL_GAP)

    /**
     * The words of the code cells of [state], in the order they are drawn: its tick limit, then its entry block, then
     * its do block.
     */
    public fun cellWords(state: StateNode): List<String> =
        listOfNotNull(LIMIT_WORD.takeIf { state.hasTickLimit }, ENTRY_WORD.takeIf { state.hasEntry }, DO_WORD.takeIf { state.hasDo })

    /**
     * Where the code cells of [state] are drawn in its box [box], by their words. Each cell is a marker with its word
     * behind it. A state without nested states shows them in a row below its name, in the middle. A state with nested
     * states shows them at the right end of the row of its name.
     */
    public fun cellBoxes(state: StateNode, box: DiagramBox): Map<String, DiagramBox> {
        val words = cellWords(state)
        var x = if (state.isComposite) box.right - NAME_PADDING / 2f - cellsWidth(state) else box.center.x - cellsWidth(state) / 2f
        val y = if (state.isComposite) box.y + (HEADER - CELL_MARK) / 2f else box.bottom - BLOCK_LINE - 2f
        return words.associateWith { word ->
            DiagramBox(x, y, cellWidth(word), CELL_MARK).also { x += it.width + 2 * CELL_GAP }
        }
    }

    /**
     * The width of the box of the caption of [edge], with a caption as wide as [text] says. The caption of a transition
     * has room for the marker of its condition at its right end. A timeout has no condition, so its caption has none.
     */
    public fun captionWidth(edge: StatechartEdge, text: TextMeasurer): Float =
        text.widthOf(edge.caption) + LABEL_PADDING + if (edge is TransitionEdge) CELL_ROOM else 0f

    /** Where the marker of the code cell of a transition is drawn: at the right end of the box [label] of its caption. */
    public fun cellBox(label: DiagramBox): DiagramBox =
        DiagramBox(label.right - CELL_MARK - CELL_GAP / 2f, label.y + (label.height - CELL_MARK) / 2f, CELL_MARK, CELL_MARK)

    private fun cellWidth(word: String): Float = CELL_MARK + CELL_GAP + word.length * CELL_CHARACTER

    private fun cellsWidth(state: StateNode): Float {
        val words = cellWords(state)
        return words.sumOf { cellWidth(it).toDouble() }.toFloat() + maxOf(words.size - 1, 0) * 2 * CELL_GAP
    }

    /** The height of the box of [state] if it has no nested states. */
    public fun leafHeight(state: StateNode): Float = if (cellWords(state).isNotEmpty()) STATE_HEIGHT + BLOCK_LINE else STATE_HEIGHT
}
