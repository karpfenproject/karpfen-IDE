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
 * A context menu with [actions]. It opens below and to the right of [offset], which is relative to the bottom-left
 * corner of its anchor, the composable it is placed in. Choosing an enabled entry first closes the menu through
 * [onDismiss] and then runs the entry. Disabled entries are shown greyed out.
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
    /** Height of one entry. Desktop menus are compact, so an entry is lower than Material's 48 dp touch target. */
    val ItemHeight = 28.dp
}
