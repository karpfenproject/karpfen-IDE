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
 *   matching-bracket boxes, find matches; tabs at 4-column stops; highlight spans of older versions mapped through
 *   `EditableText.mapOffset`.
 * - Input: keys through `state.keymap` (completion consumes keys first while open); typed text through the platform
 *   text-input API or a hidden proxy text field (works for every layout, macOS Option combinations, dead keys, IME;
 *   the chosen approach is documented in the implementation); exposes `SemanticsActions.InsertTextAtCursor` so
 *   `performTextInput` works in tests.
 * - Mouse: click, drag-select with edge auto-scroll, double/triple click, Shift+click, Cmd/Ctrl+click navigates,
 *   600 ms hover, context menu.
 * - Overlays: [overlays] hosts the popups (see [EditorOverlays]).
 *
 * @param onNavigate called with navigation targets (go to definition, Cmd/Ctrl+click), also in other files.
 */
@Composable
public fun CodeEditor(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    onNavigate: (Location) -> Unit = {},
    overlays: EditorOverlays = EditorOverlays.None,
    theme: EditorTheme = LocalEditorTheme.current,
): Unit = TODO("WP-EDITOR-VIEW: code editor composable")
