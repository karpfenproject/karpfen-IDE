package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * A context menu with [actions], shown below-right of [offset] relative to its anchor's bottom-left corner (the
 * composable it is placed in). Choosing an enabled entry closes the menu ([onDismiss]) and runs the entry; disabled
 * entries are shown greyed out.
 */
@Composable
internal fun ContextMenuPopup(actions: List<ContextMenuAction>, offset: DpOffset, onDismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = offset) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                enabled = action.isEnabled,
                modifier = Modifier.height(ContextMenuDefaults.ItemHeight),
            )
        }
    }
}

/** Fixed measures of [ContextMenuPopup]. */
internal object ContextMenuDefaults {
    /** Height of one entry (compact desktop menus instead of Material's 48 dp touch targets). */
    val ItemHeight = 28.dp
}
