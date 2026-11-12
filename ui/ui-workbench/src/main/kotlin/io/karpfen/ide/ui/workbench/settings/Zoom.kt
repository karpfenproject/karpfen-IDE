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
package io.karpfen.ide.ui.workbench.settings

import kotlin.math.roundToInt

/** One click on a zoom control. */
public enum class ZoomStep {
    /** One step larger. */
    IN,

    /** One step smaller. */
    OUT,

    /** Back to the normal size. */
    RESET,
}

/**
 * The two sizes the user can change from the status bar, with their limits. One is the scale of the whole workbench,
 * the other the font size of the editor. Both belong to the project and are stored in its `.karpfenproject` file.
 *
 * The scale of the workbench moves in tenths between [MIN_UI_SCALE] and [MAX_UI_SCALE]. It enlarges everything the
 * workbench draws, the text together with the rows, icons and gaps that hold it. Text alone would outgrow rows of a
 * fixed height. The editor's text grows with the workbench and has its font size on top of that, which moves in whole
 * points between [MIN_EDITOR_FONT_SIZE] and [MAX_EDITOR_FONT_SIZE].
 */
public object Zoom {

    /** The normal scale of the workbench. */
    public const val DEFAULT_UI_SCALE: Float = 1f

    /** The smallest scale of the workbench. */
    public const val MIN_UI_SCALE: Float = 0.7f

    /** The largest scale of the workbench. */
    public const val MAX_UI_SCALE: Float = 2f

    /** The normal font size of the editor in sp. */
    public const val DEFAULT_EDITOR_FONT_SIZE: Float = 13f

    /** The smallest font size of the editor in sp. */
    public const val MIN_EDITOR_FONT_SIZE: Float = 8f

    /** The largest font size of the editor in sp. */
    public const val MAX_EDITOR_FONT_SIZE: Float = 40f

    /** The tenths in one whole of the workbench scale. The scale is kept in tenths, so repeated steps do not drift. */
    private const val TENTHS = 10

    /** The scale of the workbench after [step] from [current], within the limits. */
    public fun uiScale(current: Float, step: ZoomStep): Float {
        val tenths = (current * TENTHS).roundToInt()
        val next = when (step) {
            ZoomStep.IN -> (tenths + 1) / TENTHS.toFloat()
            ZoomStep.OUT -> (tenths - 1) / TENTHS.toFloat()
            ZoomStep.RESET -> DEFAULT_UI_SCALE
        }
        return next.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
    }

    /** The font size of the editor after [step] from [current], within the limits. */
    public fun editorFontSize(current: Float, step: ZoomStep): Float {
        val next = when (step) {
            ZoomStep.IN -> current.roundToInt() + 1f
            ZoomStep.OUT -> current.roundToInt() - 1f
            ZoomStep.RESET -> DEFAULT_EDITOR_FONT_SIZE
        }
        return next.coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
    }

    /** [scale] as a percentage for the status bar, for example `110%`. */
    public fun percent(scale: Float): String = "${(scale * PERCENT).roundToInt()}%"

    /** The percent in one whole. */
    private const val PERCENT = 100
}
