package io.karpfen.ide.ui.editor.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.overlay.EditorOverlays
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.theme.LocalEditorTheme

/**
 * The code editor (spec §7.2). Renders [state] and sends user intents to it; contains no editing logic.
 *
 * - Rendering: virtualized (visible lines + overscan) with `TextMeasurer`/`drawText` and a per-line layout LRU cache
 *   keyed by line text + spans + font settings; gutter with one-based line numbers and diagnostic markers; current
 *   line, selection, blinking caret (solid while typing), wavy (ERROR, WARNING) and dotted (INFO) underlines,
 *   matching-bracket boxes, find matches (only while `overlays.findBar` is open); tabs at 4-column stops; highlight
 *   spans of older versions mapped through `EditableText.mapOffset`.
 * - Input: key events become `KeyStroke`s for `state.handleKey`, and the event is consumed exactly when it returns true
 *   (decision 15); typed text goes to `state.type` through the platform text-input API or a hidden proxy text field
 *   (works for every layout, macOS Option combinations, dead keys, IME; the chosen approach is documented in the
 *   implementation); exposes `SemanticsActions.InsertTextAtCursor` so `performTextInput` works in tests.
 * - Mouse: click, drag-select with edge auto-scroll, double/triple click, Shift+click (caret-model calls);
 *   Cmd/Ctrl+click → `state.goToDefinition(offset)`; pointer movement → `state.pointerHover(offset)` (the state owns the
 *   600 ms delay); right-click → `state.openContextMenuAt(offset, x, y)` with x/y in the `EditorOverlayHost` space.
 * - Pending requests: applies `state.scrollRequest` (the state also issues the caret reveal after actions and typing)
 *   and `state.focusRequested` when composed, then consumes them.
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
): Unit = TODO("WP-EDITOR-VIEW: code editor composable")
