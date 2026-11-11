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
package io.karpfen.ide.ui.workbench.dialog

/**
 * The keyboard focus order of a modal dialog. The class is pure and has no UI dependencies. The first focus stop is the
 * text field, or the dialog itself when it has no text field. The buttons follow. Only the enabled buttons take the
 * focus, and [focusable] says which those are. Tab moves to [next] and Shift+Tab moves to [previous]. Both cycle
 * through the stops and never leave the dialog.
 *
 * @param focusable for each stop, whether it can take the focus. The first stop always can.
 */
internal class DialogFocusOrder(private val focusable: List<Boolean>) {

    init {
        require(focusable.firstOrNull() == true) { "the first focus stop must take the focus" }
    }

    /** The number of focus stops. */
    val size: Int get() = focusable.size

    /** The stop Tab moves to from [stop]. */
    fun next(stop: Int): Int = step(stop, 1)

    /** The stop Shift+Tab moves to from [stop]. */
    fun previous(stop: Int): Int = step(stop, -1)

    /**
     * The first focusable stop after [stop] in [direction]. The search wraps around at both ends. It always finds a
     * stop, because the first stop is always focusable.
     */
    private fun step(stop: Int, direction: Int): Int =
        (1..size).map { Math.floorMod(stop + direction * it, size) }.first { focusable[it] }

    companion object {
        /**
         * The focus order of [presentation] while [text] is entered. A button whose answer is not possible with that
         * text is disabled and takes no focus.
         */
        fun of(presentation: DialogPresentation, text: String): DialogFocusOrder =
            DialogFocusOrder(listOf(true) + presentation.buttons.map { presentation.responseFor(it.choice, text) != null })
    }
}
