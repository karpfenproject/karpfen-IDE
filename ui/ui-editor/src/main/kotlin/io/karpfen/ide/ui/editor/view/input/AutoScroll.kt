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
 * The drag auto-scroll: dragging a selection beyond the text area scrolls it by one [step] every [INTERVAL_MILLIS]
 * while the pointer stays outside.
 */
internal object AutoScroll {

    /** Milliseconds between two auto-scroll steps while the pointer is outside. */
    const val INTERVAL_MILLIS: Long = 30

    /**
     * The scroll step for a pointer at ([x], [y]): the distance by which the pointer is outside the area of [width] x
     * [height] on each axis, at most [maxStep] pixels, with the sign of the side it left on; (0, 0) while it is inside.
     */
    fun step(x: Float, y: Float, width: Float, height: Float, maxStep: Float): Pair<Float, Float> =
        axisStep(x, width, maxStep) to axisStep(y, height, maxStep)

    /** The step on one axis: how far [position] lies outside `0..size`, signed and at most [maxStep]. */
    private fun axisStep(position: Float, size: Float, maxStep: Float): Float = when {
        position < 0f -> maxOf(position, -maxStep)
        position > size -> minOf(position - size, maxStep)
        else -> 0f
    }
}
