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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/**
 * A segment of the [StatusBar]; clickable when [onClick] is set. Holds behaviour, so it is a plain class with identity
 * equality (not a data class).
 */
@Immutable
public class StatusBarItem(
    public val id: String,
    public val text: String,
    public val tooltip: String? = null,
    public val icon: ImageVector? = null,
    public val onClick: (() -> Unit)? = null,
)

/** Test tags of [StatusBar] (for headless UI tests of the foundation and its users). */
public object StatusBarTestTags {
    /** The bar. */
    public const val BAR: String = "StatusBar"

    /** The item [id]. */
    public fun item(id: String): String = "StatusBar.item:$id"
}

/**
 * The window status bar (spec §6): [left] items aligned left, [right] items aligned right. Stateless.
 *
 * Each item shows its icon (untinted, e.g. a severity icon) and text in the small UI style; it has a tooltip when
 * [StatusBarItem.tooltip] is set and is a button when [StatusBarItem.onClick] is set. Needs `KarpfenTheme`.
 */
@Composable
public fun StatusBar(
    left: List<StatusBarItem>,
    right: List<StatusBarItem>,
    modifier: Modifier = Modifier,
) {
    val colors = KarpfenTheme.colors
    Box(modifier.fillMaxWidth().height(StatusBarDefaults.Height).background(colors.panel).testTag(StatusBarTestTags.BAR)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            left.forEach { StatusBarSegment(it) }
            Spacer(Modifier.weight(1f))
            right.forEach { StatusBarSegment(it) }
        }
    }
}

/** Fixed measures of [StatusBar]. */
internal object StatusBarDefaults {
    val Height = 24.dp
}

@Composable
private fun StatusBarSegment(item: StatusBarItem) {
    val onClick = item.onClick
    WithTooltip(item.tooltip, Modifier.fillMaxHeight()) {
        Row(
            Modifier
                .fillMaxHeight()
                .testTag(StatusBarTestTags.item(item.id))
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(item.text, style = KarpfenTheme.typography.uiSmall, color = KarpfenTheme.colors.text, maxLines = 1)
        }
    }
}
