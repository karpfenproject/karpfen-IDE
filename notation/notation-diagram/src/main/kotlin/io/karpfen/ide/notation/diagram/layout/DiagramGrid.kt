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

import kotlin.math.roundToInt

/**
 * The grid of a diagram that is arranged by hand. The middle of every box and every waypoint of a line sits on a
 * point of this grid, so elements line up without effort.
 */
public object DiagramGrid {
    /** The distance between two neighbouring points of the grid, in the units of the diagram. */
    public const val STEP: Float = 10f

    /** [value] moved to the nearest line of the grid. */
    public fun snap(value: Float): Float = (value / STEP).roundToInt() * STEP

    /** [point] moved to the nearest point of the grid. */
    public fun snap(point: DiagramPoint): DiagramPoint = DiagramPoint(snap(point.x), snap(point.y))
}
