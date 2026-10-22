package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import io.karpfen.ide.ui.editor.theme.EditorTheme
import kotlin.math.roundToInt

/**
 * The editor context menu at the request position; each entry runs its editor action by id ([onAction])
 * and is disabled — greyed out — when the action is disabled ([isEnabled]).
 *
 * The menu is a focusable desktop popup anchored at a zero-size point at ([ContextMenuRequest.x],
 * [ContextMenuRequest.y]) of the overlay area (the `EditorOverlayHost` space), so it opens there and, like every menu,
 * flips or shifts when the window has no room; Escape and clicks outside report [onDismiss], arrow keys move between
 * the entries. Escape is handled by the menu itself while one of its entries has focus (keyboard navigation) and by
 * the platform popup otherwise; either way [onDismiss] is reported once.
 */
@Composable
internal fun EditorContextMenu(
    request: ContextMenuRequest,
    items: List<EditorContextMenuItem>,
    theme: EditorTheme,
    isEnabled: (String) -> Boolean,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.offset { IntOffset(request.x.roundToInt(), request.y.roundToInt()) }.size(0.dp)) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            containerColor = theme.popupBackground,
            border = BorderStroke(1.dp, theme.popupBorder),
            modifier = Modifier
                .onPopupKey(PopupKey.CANCEL, onDismiss)
                .testTag(EditorPopupTags.CONTEXT_MENU),
        ) {
            val colors = MenuDefaults.itemColors(
                textColor = theme.foreground,
                disabledTextColor = theme.foreground.copy(alpha = PopupStyle.DISABLED_ALPHA),
            )
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = { onAction(item.actionId) },
                    enabled = isEnabled(item.actionId),
                    colors = colors,
                    modifier = Modifier.height(ITEM_HEIGHT),
                )
            }
        }
    }
}

/** Height of a menu entry (compact desktop menus instead of Material's 48 dp touch targets, like the workbench's). */
private val ITEM_HEIGHT = 28.dp
