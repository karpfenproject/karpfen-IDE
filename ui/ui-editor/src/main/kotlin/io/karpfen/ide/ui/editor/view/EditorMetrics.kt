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
package io.karpfen.ide.ui.editor.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

/**
 * The monospace cell of the editor's text style. [charWidth] is the advance of one character and may be fractional.
 * [lineHeight] is the height of one line. Both are in pixels.
 */
internal data class EditorMetrics(val charWidth: Float, val lineHeight: Float)

/** Measures the [EditorMetrics] of [style] once per measurer and style. The measurer stands for the density. */
@Composable
internal fun rememberEditorMetrics(measurer: TextMeasurer, style: TextStyle): EditorMetrics = remember(measurer, style) {
    val layout = measurer.measure(METRICS_SAMPLE, style, softWrap = false, maxLines = 1)
    EditorMetrics(
        charWidth = layout.getHorizontalPosition(METRICS_SAMPLE.length, usePrimaryDirection = true) / METRICS_SAMPLE.length,
        lineHeight = layout.getLineBottom(0) - layout.getLineTop(0),
    )
}

/** The sample text that is measured. A long run of glyphs averages out the rounding of a single glyph's advance. */
private val METRICS_SAMPLE = "M".repeat(64)
