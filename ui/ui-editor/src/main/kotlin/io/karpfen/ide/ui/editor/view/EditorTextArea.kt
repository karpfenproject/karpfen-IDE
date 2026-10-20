package io.karpfen.ide.ui.editor.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.editor.state.EditorViewport
import io.karpfen.ide.ui.editor.view.input.ProxyTextInput
import io.karpfen.ide.ui.editor.view.input.textAreaPointerInput
import io.karpfen.ide.ui.editor.view.render.rememberCaretVisible
import kotlin.math.roundToInt

/**
 * The text layer of `CodeEditor`: paints the laid-out lines with [TextAreaPainter], reports its size and the content
 * size to the viewport, sends mouse input to the editor state ([textAreaPointerInput]) and hosts the [ProxyTextInput]
 * at the caret, which owns keyboard focus. Fills its parent, the overlay host's coordinate space.
 */
@Composable
internal fun EditorTextArea(render: EditorRenderState, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val editor = render.editor
    val density = LocalDensity.current
    val cache = remember { LineLayoutCache(LINE_LAYOUT_CACHE_SIZE) }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val painter = remember(measurer, density) {
        TextAreaPainter(
            LineLayouter(measurer, cache),
            TextAreaPainter.PainterMetrics(
                caretWidth = with(density) { CARET_WIDTH.dp.toPx() },
                stroke = with(density) { STROKE.dp.toPx() },
                waveAmplitude = with(density) { WAVE_AMPLITUDE.dp.toPx() },
            ),
        )
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry = render.geometry.value
    val metrics = render.metrics.value
    val maxColumns = remember(geometry) { geometry.maxDisplayColumns() }
    SideEffect {
        editor.viewport.updateGeometry(
            viewportWidth = size.width.toFloat(),
            viewportHeight = size.height.toFloat(),
            lineHeight = metrics.lineHeight,
            contentWidth = geometry.textLeft + (maxColumns + RIGHT_PADDING_CELLS) * metrics.charWidth,
            contentHeight = (geometry.snapshot.lineCount + EditorViewport.CARET_MARGIN_LINES) * metrics.lineHeight,
        )
    }
    val caretVisible = rememberCaretVisible(render.focused.value, render.carets.value to geometry.snapshot.version)

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .textAreaPointerInput(editor) { render.liveFrame() }
            .drawBehind {
                val content = TextAreaContent(
                    frame = render.frame(),
                    text = editor.session.text,
                    highlights = render.highlights.value,
                    diagnostics = render.diagnostics.value,
                    carets = render.drawnCarets(),
                    bracket = render.bracket.value,
                    find = render.find.value.takeIf { render.findBar.value != null },
                    caretVisible = render.focused.value && caretVisible.value,
                    theme = render.theme.value,
                    textStyle = render.textStyle.value,
                    fontSize = render.settings.value.fontSize,
                )
                with(painter) { paint(content) }
            },
    ) {
        ProxyTextInput(
            onTyped = editor::type,
            onKeyStroke = editor::handleKey,
            onFocusChanged = editor::onFocusChanged,
            focusRequester = focusRequester,
            textStyle = render.textStyle.value,
            background = render.theme.value.background,
            modifier = Modifier.offset {
                val frame = render.frame()
                val caret = frame.caretRect(render.drawnCarets().first().position, 0f)
                IntOffset(
                    caret.left.roundToInt().coerceIn(0, maxOf(0, size.width - 1)),
                    caret.top.roundToInt().coerceIn(0, maxOf(0, size.height - 1)),
                )
            },
        )
    }
}

/** Layouts kept by the line layout cache (several screens of lines). */
private const val LINE_LAYOUT_CACHE_SIZE = 512

/** Cells of space right of the longest line. */
private const val RIGHT_PADDING_CELLS = 4

private const val CARET_WIDTH = 2f
private const val STROKE = 1f
private const val WAVE_AMPLITUDE = 1.5f
