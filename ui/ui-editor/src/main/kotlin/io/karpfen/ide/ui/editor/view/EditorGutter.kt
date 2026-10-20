package io.karpfen.ide.ui.editor.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import io.karpfen.ide.ui.editor.view.input.wheelScroll

/**
 * The gutter of `CodeEditor` (spec §7.2): one-based line numbers and diagnostic markers next to the laid-out lines,
 * painted by [GutterPainter], scrolling with the text (the wheel scrolls the text here too). Its width fits the line
 * count's digits.
 */
@Composable
internal fun EditorGutter(render: EditorRenderState, modifier: Modifier = Modifier) {
    val editor = render.editor
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val painter = remember(measurer) { GutterPainter(measurer, LineLayoutCache(NUMBER_CACHE_SIZE)) }
    val metrics = render.metrics.value
    val digits = if (render.settings.value.showLineNumbers) render.geometry.value.snapshot.lineCount.toString().length else 0
    val width = with(LocalDensity.current) { (metrics.charWidth * (digits + GutterPainter.EXTRA_CELLS)).toDp() }

    Canvas(
        modifier
            .width(width)
            .fillMaxHeight()
            .clipToBounds()
            .background(render.theme.value.gutterBackground)
            .wheelScroll(editor.viewport) { render.metrics.value.lineHeight },
    ) {
        val frame = render.frame()
        val content = GutterPainter.Content(
            frame = frame,
            text = editor.session.text,
            diagnostics = render.diagnostics.value,
            caretLine = frame.geometry.lineOf(render.drawnCarets().first().position),
            showLineNumbers = render.settings.value.showLineNumbers,
            theme = render.theme.value,
            textStyle = render.textStyle.value,
            fontSize = render.settings.value.fontSize,
        )
        with(painter) { paint(content) }
    }
}

/** Number layouts kept (a few screens of line numbers). */
private const val NUMBER_CACHE_SIZE = 256
