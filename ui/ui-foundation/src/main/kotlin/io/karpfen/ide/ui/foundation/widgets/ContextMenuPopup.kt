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

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import kotlinx.coroutines.delay

/**
 * An entry of a context menu: its [label], whether it [isEnabled], and what happens [onClick]. An entry with
 * [children] opens a further level of entries instead, and its own [onClick] is never run. It holds behaviour, so it
 * is a plain class with identity equality and not a data class.
 *
 * @property children the entries of the further level this entry opens, or empty for an entry that runs [onClick].
 */
@Immutable
public class ContextMenuAction(
    public val label: String,
    public val isEnabled: Boolean = true,
    public val children: List<ContextMenuAction> = emptyList(),
    public val onClick: () -> Unit,
) {
    /** An entry [label] that opens the further level [children] and does nothing by itself. */
    public constructor(label: String, children: List<ContextMenuAction>, isEnabled: Boolean = true) : this(label, isEnabled, children, {})

    /** True if the entry opens a further level of entries rather than running [onClick]. */
    public val opensLevel: Boolean
        get() = children.isNotEmpty()
}

/**
 * A context menu with [actions]. It opens below and to the right of [offset], which is relative to the bottom-left
 * corner of its anchor, the composable it is placed in. Choosing an enabled entry first closes the menu through
 * [onDismiss] and then runs the entry. Disabled entries are shown greyed out. Escape and a click outside close the menu
 * through [onDismiss] without running anything.
 *
 * An entry with children shows a chevron, which says that it opens a further level. That level opens beside the entry
 * when the pointer rests on the entry for a moment or when the entry is clicked, to the right of the menu where there
 * is room and to its left where there is none, and it scrolls when its entries do not fit the window. Resting on another
 * entry of the same level closes it again; crossing other entries on the way to it does not. Only the first level takes the keyboard focus, so Escape closes every level at once, and
 * so does a click outside all of them. Choosing an entry of any level closes every level before the entry runs. A level
 * can hold entries that open further levels in turn.
 *
 * To open the menu at a point of a larger area, such as the pointer on a canvas, place it in a box of zero size at
 * that point and pass an [offset] of zero.
 */
@Composable
public fun ContextMenuPopup(actions: List<ContextMenuAction>, offset: DpOffset, onDismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = offset) {
        ContextMenuLevel(actions, onDismiss)
    }
}

/**
 * The entries [actions] of one level of a context menu. At most one of its entries has its further level open, the
 * one the pointer rested on or clicked last. [onDismiss] closes the whole menu.
 */
@Composable
private fun ContextMenuLevel(actions: List<ContextMenuAction>, onDismiss: () -> Unit) {
    var open by remember(actions) { mutableStateOf<ContextMenuAction?>(null) }
    actions.forEach { action ->
        val hover = remember(action) { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        LaunchedEffect(hovered) {
            if (hovered) {
                delay(ContextMenuDefaults.HOVER_DELAY_MILLIS)
                open = action.takeIf { it.opensLevel && it.isEnabled }
            }
        }
        if (action.opensLevel) {
            ContextMenuLevelEntry(
                action = action,
                isOpen = open === action,
                hover = hover,
                onOpen = { open = action },
                onClose = { if (open === action) open = null },
                onDismiss = onDismiss,
            )
        } else {
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                enabled = action.isEnabled,
                modifier = Modifier.height(ContextMenuDefaults.ItemHeight),
                interactionSource = hover,
            )
        }
    }
}

/**
 * The entry [action] that opens a further level, with that level beside it while [isOpen]. A click on the entry calls
 * [onOpen]. The further level is a popup in the look of a Material menu, placed by [ContextMenuLevelPosition]. It does
 * not take the focus, so the keys still reach the first level, which closes the whole menu on Escape. It ignores a
 * press outside itself, so a press on the entry does not close it only for the release to open it again; a press
 * outside every level still closes the whole menu through the first level, and another entry of this level closes it
 * by the pointer resting there or by being chosen.
 */
@Composable
private fun ContextMenuLevelEntry(
    action: ContextMenuAction,
    isOpen: Boolean,
    hover: MutableInteractionSource,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        DropdownMenuItem(
            text = { Text(action.label) },
            onClick = onOpen,
            enabled = action.isEnabled,
            trailingIcon = { Icon(KarpfenIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(12.dp)) },
            modifier = Modifier.fillMaxWidth().height(ContextMenuDefaults.ItemHeight),
            interactionSource = hover,
        )
        if (isOpen) {
            val padding = with(LocalDensity.current) { ContextMenuDefaults.LevelPadding.roundToPx() }
            Popup(
                popupPositionProvider = remember(padding) { ContextMenuLevelPosition(padding) },
                onDismissRequest = onClose,
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Surface(
                    shape = MenuDefaults.shape,
                    color = MenuDefaults.containerColor,
                    tonalElevation = MenuDefaults.TonalElevation,
                    shadowElevation = MenuDefaults.ShadowElevation,
                ) {
                    Column(
                        Modifier.padding(vertical = ContextMenuDefaults.LevelPadding)
                            .width(IntrinsicSize.Max)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ContextMenuLevel(action.children, onDismiss)
                    }
                }
            }
        }
    }
}

/**
 * Places a further level of a context menu beside the entry that opens it, the anchor. The level goes to the right of
 * the entry where the window has room for it, and otherwise to the left of the menu the entry belongs to, so it never
 * covers that menu while either side has room; where neither has, it takes the side with more room and stays inside
 * the window. Its first entry stands level with the anchor, the level being lifted by [padding], the room above its
 * first entry; where it does not fit below that line, its last entry stands level with the anchor, and where it does
 * not fit above either, it stays inside the window. The level is never higher than the window, since a popup is
 * measured within it, and it scrolls instead.
 *
 * The anchor of the entry spans the whole width of its menu, so its left edge is the left edge of that menu.
 *
 * @param padding the room above the first and below the last entry of a level, in pixels.
 */
internal class ContextMenuLevelPosition(private val padding: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(x(anchorBounds, windowSize.width, popupContentSize.width), y(anchorBounds, windowSize.height, popupContentSize.height))

    /** The left edge of a level of [width] beside [anchor] in a window [window] wide. */
    private fun x(anchor: IntRect, window: Int, width: Int): Int {
        val right = anchor.right
        val left = anchor.left - width
        return when {
            right + width <= window -> right
            left >= 0 -> left
            window - anchor.right >= anchor.left -> right.coerceAtMost(window - width).coerceAtLeast(0)
            else -> left.coerceAtLeast(0)
        }
    }

    /** The top edge of a level of [height] beside [anchor] in a window [window] high. */
    private fun y(anchor: IntRect, window: Int, height: Int): Int {
        val downwards = anchor.top - padding
        val upwards = anchor.bottom + padding - height
        return when {
            downwards >= 0 && downwards + height <= window -> downwards
            upwards >= 0 && upwards + height <= window -> upwards
            else -> downwards.coerceAtMost(window - height).coerceAtLeast(0)
        }
    }
}

/** Fixed measures of [ContextMenuPopup]. */
internal object ContextMenuDefaults {
    /** Height of one entry. Desktop menus are compact, so an entry is lower than Material's 48 dp touch target. */
    val ItemHeight = 28.dp

    /**
     * The room above the first and below the last entry of a menu, the same that Material leaves. A further level is
     * lifted by it, so its first entry stands level with the entry that opened it.
     */
    val LevelPadding = 8.dp

    /**
     * How long the pointer rests on an entry before the further levels follow it: the level of that entry opens, and
     * an open level of another entry closes. The pointer can cross other entries on its way to an open level, and
     * passing over an entry does not open its level for a moment.
     */
    const val HOVER_DELAY_MILLIS: Long = 300L
}
