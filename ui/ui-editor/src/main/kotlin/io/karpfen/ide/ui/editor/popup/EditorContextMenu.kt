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
 * The editor context menu at the request position. Each entry runs its editor action by id through [onAction]. An
 * entry is disabled and greyed out when [isEnabled] says its action is disabled.
 *
 * The menu is a focusable desktop popup. It is anchored at a point of zero size at [ContextMenuRequest.x] and
 * [ContextMenuRequest.y] of the overlay area, which is the `EditorOverlayHost` space. So it opens there, and like every
 * menu it flips or shifts when the window has no room. Escape and clicks outside report [onDismiss]. Arrow keys move
 * between the entries. While one of the entries has focus during keyboard navigation, the menu itself handles Escape.
 * Otherwise the platform popup handles it. Either way [onDismiss] is reported once.
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

/**
 * The height of a menu entry. Desktop menus are compact, so they do not use the 48 dp touch targets of Material. The
 * menus of the workbench use the same height.
 */
private val ITEM_HEIGHT = 28.dp
