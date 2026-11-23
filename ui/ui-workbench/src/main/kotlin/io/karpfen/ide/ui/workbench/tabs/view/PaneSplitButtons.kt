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
package io.karpfen.ide.ui.workbench.tabs.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.tabs.SplitSide

/**
 * The four buttons at the right end of the tab strip of an editor pane that split the pane: Split Right, Split Left,
 * Split Down and Split Up, in this order. A click reports the side of the new pane to [onSplit], which opens the
 * pane's active file in a new pane on that side. While [enabled] is false, because the pane has no tab, the buttons
 * take no clicks and are drawn muted.
 *
 * Each button is drawn like the place button of a tool window, a small clickable box around a tinted glyph, and it
 * carries the words of its action as its content description. The buttons never take the keyboard focus, so the
 * editor keeps it.
 */
@Composable
internal fun PaneSplitButtons(enabled: Boolean, onSplit: (SplitSide) -> Unit) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SPLIT_ORDER.forEach { side -> PaneSplitButton(side, enabled) { onSplit(side) } }
    }
}

/** The button of [side]. It calls [onClick] while [enabled]. */
@Composable
private fun PaneSplitButton(side: SplitSide, enabled: Boolean, onClick: () -> Unit) {
    val label = splitButtonLabel(side)
    val colors = KarpfenTheme.colors
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .focusProperties { canFocus = false }
            .testTag(EditorAreaTestTags.splitButton(side.name))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            iconOf(side),
            contentDescription = null,
            tint = if (enabled) colors.textMuted else colors.border,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** The words of the split button of [side]: what a click does. */
internal fun splitButtonLabel(side: SplitSide): String = when (side) {
    SplitSide.RIGHT -> "Split the pane to the right"
    SplitSide.LEFT -> "Split the pane to the left"
    SplitSide.DOWN -> "Split the pane downwards"
    SplitSide.UP -> "Split the pane upwards"
}

/** The glyph of the split button of [side]. */
private fun iconOf(side: SplitSide): ImageVector = when (side) {
    SplitSide.RIGHT -> KarpfenIcons.SplitRight
    SplitSide.LEFT -> KarpfenIcons.SplitLeft
    SplitSide.DOWN -> KarpfenIcons.SplitDown
    SplitSide.UP -> KarpfenIcons.SplitUp
}

/** The order of the buttons, the same as that of the split commands in the View menu. */
private val SPLIT_ORDER: List<SplitSide> = listOf(SplitSide.RIGHT, SplitSide.LEFT, SplitSide.DOWN, SplitSide.UP)
