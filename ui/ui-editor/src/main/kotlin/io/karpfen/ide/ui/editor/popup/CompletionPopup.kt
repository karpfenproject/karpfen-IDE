package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import io.karpfen.ide.ui.editor.model.completion.CompletionUiState
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The completion list (spec §7.2): anchored below (or above, if there is no room) [anchor], at most 12 visible rows,
 * each with a kind badge, the label (matched part emphasized) and the detail; the selected row is kept visible.
 * Mouse: hover/click selects ([onSelect]), double-click accepts ([onAccept]). Keys are handled by the model.
 */
@Composable
internal fun CompletionPopup(
    state: CompletionUiState,
    anchor: Rect,
    theme: EditorTheme,
    textStyle: TextStyle,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
): Unit = TODO("WP-EDITOR-POPUPS: completion list")
