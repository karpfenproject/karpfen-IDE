package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The go-to-line popup (spec §7.2; Cmd+L on macOS, Ctrl+G elsewhere): accepts `line[:col]` (parsed with
 * [GoToLineQuery.parse]; invalid input disables Go), shows the current position and line count as a hint.
 */
@Composable
internal fun GoToLinePopup(
    currentLine: Int,
    lineCount: Int,
    theme: EditorTheme,
    onGo: (GoToLineQuery) -> Unit,
    onCancel: () -> Unit,
): Unit = TODO("WP-EDITOR-POPUPS: go-to-line popup")
