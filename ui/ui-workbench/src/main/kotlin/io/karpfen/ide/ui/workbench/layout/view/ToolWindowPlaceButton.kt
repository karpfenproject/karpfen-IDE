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
package io.karpfen.ide.ui.workbench.layout.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.layout.ToolWindowId

/**
 * The button in the frame of a detachable tool window that moves it between the main window and a window of its own.
 * Docked, [detached] is false and the button shows the detach icon. In the detached window it shows the dock icon. A
 * click calls [onClick] in both cases, which toggles the place through `LayoutState.toggleDetached`.
 *
 * The button is drawn like the close button of an editor tab, a small clickable box around a tinted glyph, and it
 * carries the words of its action as its content description.
 */
@Composable
internal fun ToolWindowPlaceButton(id: ToolWindowId, detached: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = placeButtonLabel(id, detached)
    Box(
        modifier
            .size(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .testTag(LayoutViewTestTags.placeButton(id))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (detached) KarpfenIcons.Dock else KarpfenIcons.Detach,
            contentDescription = null,
            tint = KarpfenTheme.colors.textMuted,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** The words of the place button of [id]: what a click does, given whether the tool window is [detached]. */
internal fun placeButtonLabel(id: ToolWindowId, detached: Boolean): String =
    if (detached) "Dock ${id.title} into the main window" else "Detach ${id.title} into its own window"

/** Test tags of the layout views. */
public object LayoutViewTestTags {
    /** The tag of the place button of the tool window [id], docked and detached alike. */
    public fun placeButton(id: ToolWindowId): String = "Layout.placeButton:${id.name}"

    /** The tag of the frame of the tool window [id] inside its own window. */
    public fun detachedToolWindow(id: ToolWindowId): String = "Layout.detachedToolWindow:${id.name}"
}
