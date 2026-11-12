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
import kotlin.math.roundToInt

/**
 * The measures of a drawn statechart, in the diagram's own units. The automatic layout and the layout by hand share
 * them, so a state has the same size in both.
 */
public object StatechartMetrics {
    /** The room at the top of the box of a state with nested states, which holds its name. */
    public const val HEADER: Float = 26f

    /** The height of a state without nested states. */
    public const val STATE_HEIGHT: Float = 36f

    /** The extra height of a state that has an entry or a do block, for the line that says so. */
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

    /** The distance between two elements that are placed next to each other automatically. */
    public const val GAP: Float = 40f

    /**
     * The step of the grid. The middle of every box and every waypoint of a transition sits on a point of this grid
     * once the layout is made by hand.
     */
    public const val GRID: Float = 10f

    /** [value] moved to the nearest line of the grid. */
    public fun snap(value: Float): Float = (value / GRID).roundToInt() * GRID

    /** [point] moved to the nearest point of the grid. */
    public fun snap(point: DiagramPoint): DiagramPoint = DiagramPoint(snap(point.x), snap(point.y))

    /** The width of the box of [state] if it has no nested states, with a name as wide as [text] says. */
    public fun leafWidth(state: StateNode, text: TextMeasurer): Float = maxOf(text.widthOf(state.name) + 2 * NAME_PADDING, MIN_STATE_WIDTH)

    /** The height of the box of [state] if it has no nested states. */
    public fun leafHeight(state: StateNode): Float = if (state.hasEntry || state.hasDo) STATE_HEIGHT + BLOCK_LINE else STATE_HEIGHT
}
