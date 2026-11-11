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
 * The gutter of `CodeEditor`. It shows one-based line numbers and diagnostic markers next to the laid-out lines, and
 * [GutterPainter] paints them. It scrolls with the text, and the mouse wheel scrolls the text here too. Its width fits
 * the digits of the line count.
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

/** The number of line number layouts kept. That is a few screens of line numbers. */
private const val NUMBER_CACHE_SIZE = 256
