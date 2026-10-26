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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.model.caret.CaretSnapshot
import io.karpfen.ide.ui.editor.model.caret.TabStops
import io.karpfen.ide.ui.editor.model.find.FindResult
import io.karpfen.ide.ui.editor.state.BracketMatchSnapshot
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.state.HighlightSnapshot
import io.karpfen.ide.ui.editor.state.ScrollRequest
import io.karpfen.ide.ui.editor.state.ViewportSnapshot
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.view.geometry.TextAreaFrame
import io.karpfen.ide.ui.editor.view.geometry.TextGeometry

/**
 * The Compose side of one composed `CodeEditor` (a view helper, not editor logic): the editor state's flows as Compose
 * state, the text style and cell metrics, and the [TextGeometry] of the current snapshot. Reading these in the draw or
 * layout phase invalidates only that phase, so scrolling and caret blinking do not recompose the editor.
 */
@Stable
internal class EditorRenderState(
    val editor: CodeEditorState,
    val theme: State<EditorTheme>,
    val textStyle: State<TextStyle>,
    val settings: State<EditorSettings>,
    val metrics: State<EditorMetrics>,
    val geometry: State<TextGeometry>,
    val viewport: State<ViewportSnapshot>,
    val carets: State<CaretSnapshot>,
    val highlights: State<HighlightSnapshot>,
    val diagnostics: State<VersionedDiagnostics>,
    val bracket: State<BracketMatchSnapshot>,
    val find: State<FindResult>,
    val findBar: State<FindBarMode?>,
    val focused: State<Boolean>,
    val scrollRequest: State<ScrollRequest?>,
) {

    /** The text area at the scroll position of the last composition (drawing, overlay geometry). */
    fun frame(): TextAreaFrame = TextAreaFrame(geometry.value, viewport.value)

    /** The text area at the viewport's current scroll position (input, which may run between frames). */
    fun liveFrame(): TextAreaFrame = TextAreaFrame(geometry.value, editor.viewport.state.value)

    /** The carets in the coordinates of the drawn snapshot. */
    fun drawnCarets(): List<Caret> = caretsIn(geometry.value.snapshot, carets.value)

    /**
     * [carets] in [snapshot]: as they are when they refer to it; mapped forward with `EditableText.mapOffset` (the point
     * mapping of carets) when they are older; clamped when they are newer (the next frame draws the new text).
     */
    private fun caretsIn(snapshot: TextSnapshot, carets: CaretSnapshot): List<Caret> {
        if (carets.version == snapshot.version) return carets.carets
        val text = editor.session.text
        fun point(offset: Int): Int {
            val mapped = if (carets.version < snapshot.version) {
                text.mapOffset(offset, carets.version, snapshot.version, Bias.RIGHT)
            } else {
                null
            }
            return (mapped ?: offset).coerceIn(0, snapshot.length)
        }
        return carets.carets.map { Caret(point(it.anchor), point(it.position)) }
    }
}

/**
 * Collects [state]'s flows and derives the geometry for [theme]. Every argument of the holder is a remembered `State`
 * of this composition, so one holder per editor state suffices.
 */
@Composable
internal fun rememberEditorRenderState(state: CodeEditorState, theme: EditorTheme): EditorRenderState {
    val settings = state.settings.collectAsState()
    val snapshot = state.snapshot.collectAsState()
    val themeState = rememberUpdatedState(theme)
    val fontSize = settings.value.fontSize
    val textStyle = rememberUpdatedState(
        remember(theme.foreground, fontSize) {
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = theme.foreground)
        },
    )
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val metrics = rememberUpdatedState(rememberEditorMetrics(measurer, textStyle.value))
    val tabWidth = settings.value.tabDisplayWidth
    val geometry = rememberUpdatedState(
        remember(snapshot.value, tabWidth, metrics.value) {
            TextGeometry(
                snapshot = snapshot.value,
                tabStops = TabStops(tabWidth),
                charWidth = metrics.value.charWidth,
                lineHeight = metrics.value.lineHeight,
                textLeft = metrics.value.charWidth * TEXT_LEFT_PADDING_CELLS,
            )
        },
    )
    val viewport = state.viewport.state.collectAsState()
    val carets = state.caret.state.collectAsState()
    val highlights = state.highlights.collectAsState()
    val diagnostics = state.session.diagnostics.collectAsState()
    val bracket = state.bracketMatch.collectAsState()
    val find = state.find.state.collectAsState()
    val findBar = state.overlays.findBar.collectAsState()
    val focused = state.isFocused.collectAsState()
    val scrollRequest = state.scrollRequest.collectAsState()
    return remember(state) {
        EditorRenderState(
            editor = state,
            theme = themeState,
            textStyle = textStyle,
            settings = settings,
            metrics = metrics,
            geometry = geometry,
            viewport = viewport,
            carets = carets,
            highlights = highlights,
            diagnostics = diagnostics,
            bracket = bracket,
            find = find,
            findBar = findBar,
            focused = focused,
            scrollRequest = scrollRequest,
        )
    }
}

/** Space left of the text, in cells. */
private const val TEXT_LEFT_PADDING_CELLS = 0.5f
