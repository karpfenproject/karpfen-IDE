package io.karpfen.ide.ui.foundation.layout

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import java.awt.Cursor

/**
 * The drag handle of a [SplitPane]: draws the 1 dp divider line at its centre, shows a resize cursor and reports the
 * pointer movement along the main axis from the press on (primary button or touch; other buttons are ignored).
 */
@Composable
internal fun SplitDivider(orientation: SplitOrientation, onDragStart: () -> Unit, onDrag: (Float) -> Unit) {
    val horizontal = orientation == SplitOrientation.HORIZONTAL
    val lineColor = KarpfenTheme.colors.border
    val currentOnDragStart = rememberUpdatedState(onDragStart)
    val currentOnDrag = rememberUpdatedState(onDrag)
    val cursor = remember(horizontal) {
        PointerIcon(Cursor(if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
    }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(SplitPaneTestTags.DIVIDER)
            .pointerHoverIcon(cursor)
            .drawBehind {
                val thickness = SplitPaneDefaults.DividerThickness.toPx()
                if (horizontal) {
                    drawRect(lineColor, Offset(size.width / 2 - thickness / 2, 0f), Size(thickness, size.height))
                } else {
                    drawRect(lineColor, Offset(0f, size.height / 2 - thickness / 2), Size(size.width, thickness))
                }
            }
            .pointerInput(horizontal) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val buttons = currentEvent.buttons
                    if (buttons.isSecondaryPressed || buttons.isTertiaryPressed) return@awaitEachGesture
                    down.consume()
                    currentOnDragStart.value()
                    drag(down.id) { change ->
                        val delta = change.positionChange()
                        change.consume()
                        currentOnDrag.value(if (horizontal) delta.x else delta.y)
                    }
                }
            },
    )
}
