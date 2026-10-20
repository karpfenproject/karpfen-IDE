package io.karpfen.ide.ui.editor.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

/**
 * The monospace cell of the editor's text style: the advance of one character ([charWidth], fractional) and the height
 * of one line ([lineHeight]), both in pixels.
 */
internal data class EditorMetrics(val charWidth: Float, val lineHeight: Float)

/** Measures the [EditorMetrics] of [style] once per measurer (density) and style. */
@Composable
internal fun rememberEditorMetrics(measurer: TextMeasurer, style: TextStyle): EditorMetrics = remember(measurer, style) {
    val layout = measurer.measure(METRICS_SAMPLE, style, softWrap = false, maxLines = 1)
    EditorMetrics(
        charWidth = layout.getHorizontalPosition(METRICS_SAMPLE.length, usePrimaryDirection = true) / METRICS_SAMPLE.length,
        lineHeight = layout.getLineBottom(0) - layout.getLineTop(0),
    )
}

/** Measured to average out rounding of a single glyph's advance. */
private val METRICS_SAMPLE = "M".repeat(64)
