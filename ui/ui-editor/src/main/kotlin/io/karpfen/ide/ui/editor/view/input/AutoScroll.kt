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
package io.karpfen.ide.ui.editor.view.input

/**
 * The automatic scrolling while a selection is dragged. Dragging beyond the text area scrolls it by one [step] every
 * [INTERVAL_MILLIS] while the pointer stays outside.
 */
internal object AutoScroll {

    /** Milliseconds between two auto-scroll steps while the pointer is outside. */
    const val INTERVAL_MILLIS: Long = 30

    /**
     * Returns the scroll step for a pointer at [x] and [y]. On each axis, the step is the distance by which the pointer
     * lies outside the area of [width] by [height], at most [maxStep] pixels. Its sign tells the side the pointer left
     * on. While the pointer is inside, the step is zero on both axes.
     */
    fun step(x: Float, y: Float, width: Float, height: Float, maxStep: Float): Pair<Float, Float> =
        axisStep(x, width, maxStep) to axisStep(y, height, maxStep)

    /** Returns the step on one axis. That is how far [position] lies outside `0..size`, with a sign and at most [maxStep]. */
    private fun axisStep(position: Float, size: Float, maxStep: Float): Float = when {
        position < 0f -> maxOf(position, -maxStep)
        position > size -> minOf(position - size, maxStep)
        else -> 0f
    }
}
