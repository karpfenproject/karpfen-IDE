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

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.overlay.EditorOverlays
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.theme.LocalEditorTheme
import io.karpfen.ide.ui.editor.view.scroll.ScrollAxis
import io.karpfen.ide.ui.editor.view.scroll.ViewportScrollbarAdapter

/**
 * The code editor. It renders [state] and sends user intents to it. It contains no editing logic.
 *
 * Rendering is virtualized. Only the visible lines plus some overscan are laid out, with `TextMeasurer` and `drawText`.
 * A least recently used cache keeps the layout of each line, keyed by line text, spans and font settings. The gutter
 * shows one-based line numbers and diagnostic markers. The text area draws the current line, the selection and a
 * blinking caret that stays solid while the user types. Errors and warnings get wavy underlines, and information gets
 * dotted ones. Matching brackets get boxes. Find matches are drawn only while `overlays.findBar` is open. Tabs advance
 * to stops of `EditorSettings.tabDisplayWidth` through `TabStops`, which hit-testing and the caret x use as well.
 *
 * Ranges of older text versions are mapped with `EditableText.mapRange` before they are drawn. Such ranges are
 * highlight spans, diagnostics while `VersionedDiagnostics` lags behind, and bracket matches. They are never mapped by
 * mapping their bounds separately. A range that does not map is not drawn.
 *
 * Key events become `KeyStroke`s for `state.handleKey`. The event is consumed exactly when that call returns true, so
 * unhandled strokes reach the menu accelerators of the window. Typed text goes to `state.type` through a hidden proxy
 * text field at the caret. This works for every keyboard layout, the Option combinations of macOS, dead keys and input
 * methods, as `ProxyTextInput` describes. Focus changes of the text area
 * go to `state.onFocusChanged`.
 *
 * The node of this composable exposes `SemanticsActions.InsertTextAtCursor` and `RequestFocus`, so `performTextInput`
 * works in tests. It exposes `SetText` as well, because text input in tests requires it next to
 * `InsertTextAtCursor`. But `SetText` refuses, because replacing the whole document is not an editor gesture.
 *
 * The mouse handling only hit-tests. A primary press calls `state.pointerPressed(offset, clickCount, modifiers)`. A
 * drag calls `state.pointerDragged(offset)` and runs a timer for automatic scrolling at the edges. A release calls
 * `state.pointerReleased()`. The state decides about caret moves, word and line selection, extending with Shift and go
 * to definition with the primary modifier. Pointer movement calls `state.pointerHover(offset)`, and the state owns the
 * 600 ms hover delay. A right-click calls `state.openContextMenuAt(offset, x, y)` with x and y in the
 * `EditorOverlayHost` space. The wheel and the desktop scrollbars scroll `state.viewport`.
 *
 * When composed, the editor applies `state.scrollRequest` and `state.focusRequested` and then consumes them. The state
 * also issues the caret reveal after actions, typing and find navigation as a scroll request.
 *
 * [overlays] hosts the popups, as described on [EditorOverlays].
 *
 * @param onNavigate is for simple embeddings without a workbench. While the editor is composed, it collects
 *   `state.navigationRequests` and calls this with each target. The workbench collects the requests itself for the
 *   whole life of the editor and leaves the default.
 */
@Composable
public fun CodeEditor(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    onNavigate: (Location) -> Unit = {},
    overlays: EditorOverlays = EditorOverlays.None,
    theme: EditorTheme = LocalEditorTheme.current,
) {
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(state) { state.navigationRequests.collect { currentOnNavigate(it) } }

    val render = rememberEditorRenderState(state, theme)
    val focusRequester = remember { FocusRequester() }
    PendingViewRequests(render, focusRequester)

    val caretWidth = with(LocalDensity.current) { CARET_HOST_WIDTH.dp.toPx() }
    val host = remember(render, caretWidth) {
        TextAreaOverlayHost(
            state = state,
            themeState = render.theme,
            textStyleState = render.textStyle,
            frame = { render.frame() },
            liveFrame = { render.liveFrame() },
            scrollPending = { render.scrollRequest.value != null },
            primaryCaret = { render.drawnCarets().first().position },
            caretWidth = caretWidth,
        )
    }
    val focused = render.focused.value

    Column(
        modifier
            .background(theme.background)
            .semantics {
                this.focused = focused
                isEditable = true
                requestFocus {
                    focusRequester.requestFocus()
                    true
                }
                insertTextAtCursor { text ->
                    state.type(text.text)
                    true
                }
                setText { false }
            },
    ) {
        overlays.Header(host)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            EditorGutter(render)
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val readOnly = state.settings.collectAsState().value.readOnly
                EditorTextArea(render, focusRequester, if (readOnly) Modifier.alpha(READ_ONLY_ALPHA) else Modifier)
                VerticalScrollbar(
                    adapter = remember(state) { ViewportScrollbarAdapter(render.viewport, state.viewport, ScrollAxis.VERTICAL) },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                HorizontalScrollbar(
                    adapter = remember(state) { ViewportScrollbarAdapter(render.viewport, state.viewport, ScrollAxis.HORIZONTAL) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
                overlays.Popups(host)
            }
        }
    }
}

/** The width of the caret rectangle reported to the overlays, in dp. */
private const val CARET_HOST_WIDTH = 2f

/** How strongly the text of a read-only document is drawn. The rest is the background showing through. */
private const val READ_ONLY_ALPHA = 0.55f
