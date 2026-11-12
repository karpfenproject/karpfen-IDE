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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/**
 * A segment of the [StatusBar]. It is clickable when [onClick] is set. It holds behaviour, so it is a plain class with
 * identity equality and not a data class.
 *
 * @property id identifies the segment in test tags.
 * @property text the segment's label.
 * @property tooltip shown while the pointer rests on the segment, or null for none.
 * @property icon drawn untinted before the text, such as a severity icon, or null for none.
 * @property onClick runs when the segment is clicked, or null for a segment that is no button.
 * @property isMuted true for a segment that is greyed out, such as a label whose action is not available right now.
 */
@Immutable
public class StatusBarItem(
    public val id: String,
    public val text: String,
    public val tooltip: String? = null,
    public val icon: ImageVector? = null,
    public val onClick: (() -> Unit)? = null,
    public val isMuted: Boolean = false,
)

/** Test tags of [StatusBar], for headless UI tests of the foundation and of the modules that use it. */
public object StatusBarTestTags {
    /** The bar. */
    public const val BAR: String = "StatusBar"

    /** The item [id]. */
    public fun item(id: String): String = "StatusBar.item:$id"
}

/**
 * The window status bar, with the [left] items aligned left and the [right] items aligned right. It is stateless.
 *
 * Each item shows its icon untinted and its text in the small UI style. It has a tooltip when
 * [StatusBarItem.tooltip] is set. When [StatusBarItem.onClick] is set, the item is a button that never takes the
 * keyboard focus. The bar needs `KarpfenTheme`.
 *
 * A window may show a second bar of this kind, such as the bar below an editor. It then passes its own [tag], so the
 * two bars can be told apart in tests.
 */
@Composable
public fun StatusBar(
    left: List<StatusBarItem>,
    right: List<StatusBarItem>,
    modifier: Modifier = Modifier,
    tag: String = StatusBarTestTags.BAR,
) {
    val colors = KarpfenTheme.colors
    Box(modifier.fillMaxWidth().height(StatusBarDefaults.Height).background(colors.panel).testTag(tag)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            left.forEach { StatusBarSegment(it) }
            Spacer(Modifier.weight(1f))
            right.forEach { StatusBarSegment(it) }
        }
    }
}

/**
 * Makes a segment a button that runs [onClick] without taking the keyboard focus, so clicking it, for example to show
 * a tool window, leaves the focus where the user works, such as in the editor.
 */
private fun Modifier.statusBarButton(onClick: () -> Unit): Modifier =
    focusProperties { canFocus = false }.clickable(role = Role.Button, onClick = onClick)

/** Fixed measures of [StatusBar]. */
internal object StatusBarDefaults {
    /** Height of the bar. */
    val Height = 24.dp
}

/** One [item] of the bar: icon and text, with its tooltip, and a button when it has a click action. */
@Composable
private fun StatusBarSegment(item: StatusBarItem) {
    val onClick = item.onClick
    WithTooltip(item.tooltip, Modifier.fillMaxHeight()) {
        Row(
            Modifier
                .fillMaxHeight()
                .testTag(StatusBarTestTags.item(item.id))
                .then(if (onClick != null) Modifier.statusBarButton(onClick) else Modifier)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            val colors = KarpfenTheme.colors
            Text(item.text, style = KarpfenTheme.typography.uiSmall, color = if (item.isMuted) colors.textMuted else colors.text, maxLines = 1)
        }
    }
}
