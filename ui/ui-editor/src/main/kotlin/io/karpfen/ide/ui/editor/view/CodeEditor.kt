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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The code editor. Renders [state] and sends user intents to it; contains no editing logic.
 *
 * - Rendering: virtualized (visible lines + overscan) with `TextMeasurer`/`drawText` and a per-line layout LRU cache
 *   keyed by line text + spans + font settings; gutter with one-based line numbers and diagnostic markers; current
 *   line, selection, blinking caret (solid while typing), wavy (ERROR, WARNING) and dotted (INFO) underlines,
 *   matching-bracket boxes, find matches (only while `overlays.findBar` is open); tabs at `EditorSettings.tabDisplayWidth`
 *   stops with `TabStops` (also for hit-testing and the caret x); ranges of older versions (highlight spans,
 *   diagnostics while `VersionedDiagnostics` lags, bracket matches) mapped with `EditableText.mapRange` — never by
 *   mapping their bounds separately; a range that does not map is not drawn.
 * - Input: key events become `KeyStroke`s for `state.handleKey`, and the event is consumed exactly when it returns true
 *   (unhandled strokes reach the window's menu accelerators); typed text goes to `state.type` through a hidden proxy
 *   text field at the caret (works for every layout, macOS Option combinations, dead keys, IME; the approach and why
 *   it was chosen are documented on the internal `ProxyTextInput`); exposes `SemanticsActions.InsertTextAtCursor` (and
 *   `RequestFocus`) on this composable's node so `performTextInput` works in tests. `SetText` is exposed as well,
 *   because text input in tests requires it next to `InsertTextAtCursor`, but it refuses: replacing the whole document
 *   is not an editor gesture. Focus changes of the text area go to `state.onFocusChanged`.
 * - Mouse: hit-testing only — primary press → `state.pointerPressed(offset, clickCount, modifiers)`, drag →
 *   `state.pointerDragged(offset)` (plus the edge auto-scroll timer), release → `state.pointerReleased()`; the state
 *   decides caret move, word/line selection, Shift-extension and primary-modifier go-to-definition. Pointer movement →
 *   `state.pointerHover(offset)` (the state owns the 600 ms delay); right-click → `state.openContextMenuAt(offset, x, y)`
 *   with x/y in the `EditorOverlayHost` space. The wheel and the desktop scrollbars scroll `state.viewport`.
 * - Pending requests: applies `state.scrollRequest` (the state also issues the caret reveal after actions, typing and
 *   find navigation) and `state.focusRequested` when composed, then consumes them.
 * - Overlays: [overlays] hosts the popups (see [EditorOverlays]).
 *
 * @param onNavigate for simple embeddings without a workbench: collects `state.navigationRequests` while composed and
 *   calls this with each target. The workbench collects the requests itself (for the editor's whole life) and leaves
 *   the default.
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
                EditorTextArea(render, focusRequester)
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

/** Width of the caret rectangle reported to the overlays. */
private const val CARET_HOST_WIDTH = 2f
