package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/** A press on a tree row, in the row's coordinates. */
internal sealed interface TreeRowPress {
    /** A primary-button (or touch) press at [x] px, at [uptimeMillis]. */
    data class Primary(val x: Float, val uptimeMillis: Long) : TreeRowPress

    /** A secondary-button press at [position] px. */
    data class Secondary(val position: Offset) : TreeRowPress
}

/**
 * An open context menu of a tree row: its [actions] at [offset] from the row's bottom-left corner.
 *
 * @property key the key of the node whose row shows the menu.
 */
internal class TreeRowMenu<K>(val key: K, val actions: List<ContextMenuAction>, val offset: DpOffset)

/** Fixed measures of [TreeView]. */
internal object TreeViewDefaults {
    /** Height of every row; fixed, so scroll offsets of rows that are not composed are exact. */
    val RowHeight: Dp = 22.dp

    /** Indentation per nesting level. */
    val Indent: Dp = 16.dp

    /** Space before the chevron of a root row. */
    val StartPadding: Dp = 4.dp

    /** Width of the chevron area, which toggles an expandable node when pressed. */
    val ChevronWidth: Dp = 16.dp

    /** Size of a node's icon. */
    val IconSize: Dp = 16.dp

    /** Where the chevron of a row at [depth] starts. */
    fun chevronStart(depth: Int): Dp = StartPadding + Indent * depth
}

/**
 * One row of a [TreeView]: indentation, chevron (expandable nodes), icon, label; the selection background (lighter
 * while the tree is not focused); reports presses to [onPress] and shows [menu] if it belongs to this row.
 */
@Composable
internal fun <K> TreeRowView(
    row: TreeRow<K>,
    isSelected: Boolean,
    isExpanded: Boolean,
    isTreeFocused: Boolean,
    icon: (@Composable (TreeNode<K>) -> Unit)?,
    menu: TreeRowMenu<K>?,
    onPress: (TreeRowPress) -> Unit,
    onDismissMenu: () -> Unit,
) {
    val colors = KarpfenTheme.colors
    val currentOnPress = rememberUpdatedState(onPress)
    val background = when {
        !isSelected -> Color.Transparent
        isTreeFocused -> colors.selection
        else -> colors.selection.copy(alpha = 0.55f)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(TreeViewDefaults.RowHeight)
            .background(background)
            .testTag(TreeViewTestTags.row(row.node.key))
            .semantics(mergeDescendants = true) { selected = isSelected }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val change = event.changes.first()
                        val press = when {
                            event.buttons.isSecondaryPressed -> TreeRowPress.Secondary(change.position)
                            event.buttons.isPrimaryPressed || change.type == PointerType.Touch ->
                                TreeRowPress.Primary(change.position.x, change.uptimeMillis)
                            else -> null
                        }
                        press?.let(currentOnPress.value)
                    }
                }
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = TreeViewDefaults.chevronStart(row.depth)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(TreeViewDefaults.ChevronWidth), contentAlignment = Alignment.Center) {
                if (row.isExpandable) {
                    Icon(
                        imageVector = if (isExpanded) KarpfenIcons.ChevronDown else KarpfenIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Box(Modifier.size(TreeViewDefaults.IconSize), contentAlignment = Alignment.Center) {
                if (icon != null) {
                    icon(row.node)
                } else {
                    row.node.icon?.let { Image(it, contentDescription = null, Modifier.size(TreeViewDefaults.IconSize)) }
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = row.node.label,
                style = KarpfenTheme.typography.ui,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menu != null) ContextMenuPopup(menu.actions, menu.offset, onDismissMenu)
    }
}
