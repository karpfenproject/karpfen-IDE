package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/**
 * One tab of a [TabStrip]: icon, title, dirty dot, close button; the selected tab has the window background and an
 * accent underline. A primary click selects, a middle click (consumed before the selection sees it) or the close
 * button closes.
 */
@Composable
internal fun TabView(
    tab: TabItem,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KarpfenTheme.colors
    val currentOnClose = rememberUpdatedState(onClose)
    val accent = colors.accent
    WithTooltip(tab.tooltip, modifier.fillMaxHeight()) {
        Row(
            Modifier
                .fillMaxHeight()
                .testTag(TabStripTestTags.tab(tab.id))
                .background(if (isSelected) colors.window else Color.Transparent)
                .drawBehind {
                    if (isSelected) {
                        val thickness = 2.dp.toPx()
                        drawRect(accent, Offset(0f, size.height - thickness), Size(size.width, thickness))
                    }
                }
                .pointerInput(tab.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                                event.changes.forEach { it.consume() }
                                currentOnClose.value(tab.id)
                            }
                        }
                    }
                }
                .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab.id) })
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tab.icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = tab.title,
                style = KarpfenTheme.typography.ui,
                color = if (isSelected) colors.text else colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tab.isDirty) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .testTag(TabStripTestTags.dirty(tab.id))
                        .semantics { contentDescription = "Unsaved changes" },
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .testTag(TabStripTestTags.close(tab.id))
                    .clickable(role = Role.Button, onClickLabel = "Close") { onClose(tab.id) }
                    .semantics { contentDescription = "Close ${tab.title}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(KarpfenIcons.Close, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
            }
        }
    }
}
