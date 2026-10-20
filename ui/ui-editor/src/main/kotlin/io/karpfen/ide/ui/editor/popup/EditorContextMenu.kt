package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The editor context menu (spec §7.2) at the request position; each entry runs its editor action by id ([onAction])
 * and is disabled when the action is disabled ([isEnabled]).
 */
@Composable
internal fun EditorContextMenu(
    request: ContextMenuRequest,
    items: List<EditorContextMenuItem>,
    theme: EditorTheme,
    isEnabled: (String) -> Boolean,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
): Unit = TODO("WP-EDITOR-POPUPS: context menu")
