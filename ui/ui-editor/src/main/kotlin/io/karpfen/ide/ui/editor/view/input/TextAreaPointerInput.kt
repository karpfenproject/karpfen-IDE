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
package io.karpfen.ide.ui.editor.view.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import io.karpfen.ide.ui.editor.state.EditorViewModel
import io.karpfen.ide.ui.editor.state.EditorViewport
import io.karpfen.ide.ui.editor.view.geometry.TextAreaFrame
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The mouse input of the text area. It only hit-tests and runs the timer for automatic scrolling during a drag.
 * Everything else is an intent of [model], which does not need Compose. Positions are in text area coordinates, the
 * `EditorOverlayHost` space.
 *
 * A primary press calls `pointerPressed(offset, clickCount, modifiers)`, and [ClickCounter] counts the clicks. A drag
 * calls `pointerDragged(offset)` and scrolls automatically while the pointer is outside the area. A release calls
 * `pointerReleased()`. A secondary press calls `openContextMenuAt(offset, x, y)`.
 *
 * Movement without buttons calls `pointerHover` with the offset of the character under the pointer, or with null
 * outside the text. Leaving the area calls `pointerHover(null)`. The mouse wheel scrolls the viewport through
 * [wheelScroll], and Shift+wheel scrolls horizontally.
 *
 * Presses are taken in the initial pass and consumed, so the proxy text field below them never reacts to the mouse.
 *
 * A press while a completion preview shows is hit-tested with the frame that draws the preview, so it lands on the text
 * the user sees. The press closes the list, and the lines below the preview move back under the pointer. Two rules
 * keep the rest of the gesture on what the user pointed at. A press that continues the click series of such a press,
 * the second click of a double click, is hit-tested with the same frame as the first one. And the pointer extends the
 * selection only once it has moved farther than the touch slop from the press, so a hand that shakes a little during
 * the click does not select up to the line that moved under the pointer.
 *
 * @param frame returns the text area at the current scroll position, or null before the first layout. It is read at
 *   every event.
 */
internal fun Modifier.textAreaPointerInput(model: EditorViewModel, frame: () -> TextAreaFrame?): Modifier =
    pointerInput(model) {
        val clicks = ClickCounter(viewConfiguration.doubleTapTimeoutMillis, viewConfiguration.touchSlop)
        var previewSeries: TextAreaFrame? = null
        coroutineScope {
            val timers: CoroutineScope = this
            awaitEachGesture {
                val event = awaitDownWithAnyButton()
                val down = event.changes.first { it.changedToDownIgnoreConsumed() }
                val live = frame() ?: return@awaitEachGesture
                down.consume()
                val position = down.position
                if (event.buttons.isSecondaryPressed) {
                    model.openContextMenuAt(live.offsetAt(position.x, position.y), position.x, position.y)
                    return@awaitEachGesture
                }
                if (!event.buttons.isPrimaryPressed) return@awaitEachGesture
                val count = clicks.press(down.uptimeMillis, position.x, position.y)
                if (count == 1) previewSeries = live.takeIf { it.geometry.preview != null }
                val at = previewSeries?.takeIf { it.geometry.snapshot.version == live.geometry.snapshot.version } ?: live
                model.pointerPressed(at.offsetAt(position.x, position.y), count, event.keyboardModifiers.toKeyModifiers())
                var dragging = at.geometry.preview == null
                var last = position
                var autoScroll: Job? = null
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!dragging) dragging = (change.position - position).getDistance() > viewConfiguration.touchSlop
                        if (dragging && change.positionChanged()) {
                            last = change.position
                            frame()?.let { model.pointerDragged(it.offsetAt(last.x, last.y)) }
                            val outside = last.x < 0f || last.y < 0f || last.x > size.width || last.y > size.height
                            if (outside && autoScroll?.isActive != true) {
                                val area = size
                                autoScroll = timers.launch { autoScroll(model, frame, { last }, area) }
                            } else if (!outside) {
                                autoScroll?.cancel()
                            }
                        }
                        change.consume()
                    }
                } finally {
                    autoScroll?.cancel()
                    model.pointerReleased()
                }
            }
        }
    }.pointerInput(model) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> if (!event.buttons.areAnyPressed) {
                        model.pointerHover(frame()?.characterAt(change.position.x, change.position.y))
                    }
                    PointerEventType.Exit -> model.pointerHover(null)
                }
            }
        }
    }.wheelScroll(model.viewport) { frame()?.geometry?.lineHeight ?: 0f }

/**
 * Lets the mouse wheel scroll [viewport] by [WHEEL_LINES] lines of [lineHeight] per notch. Shift+wheel scrolls
 * horizontally. The text area and the gutter both use it.
 */
internal fun Modifier.wheelScroll(viewport: EditorViewport, lineHeight: () -> Float): Modifier =
    pointerInput(viewport) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val step = lineHeight() * WHEEL_LINES
                val delta = change.scrollDelta
                if (event.keyboardModifiers.isShiftPressed && delta.x == 0f) {
                    viewport.scrollBy(delta.y * step, 0f)
                } else {
                    viewport.scrollBy(delta.x * step, delta.y * step)
                }
                change.consume()
            }
        }
    }

/**
 * Waits for the next event in which a pointer went down with any button, taken in the initial pass. `awaitFirstDown`
 * cannot be used, because it reports only the primary mouse button on desktop.
 */
private suspend fun AwaitPointerEventScope.awaitDownWithAnyButton(): PointerEvent {
    var event = awaitPointerEvent(PointerEventPass.Initial)
    while (event.changes.none { it.changedToDownIgnoreConsumed() }) event = awaitPointerEvent(PointerEventPass.Initial)
    return event
}

/**
 * Scrolls while the drag position [last] is outside the area. After each step it extends the selection to the point
 * under the pointer.
 */
private suspend fun CoroutineScope.autoScroll(
    model: EditorViewModel,
    frame: () -> TextAreaFrame?,
    last: () -> Offset,
    area: IntSize,
) {
    while (isActive) {
        delay(AutoScroll.INTERVAL_MILLIS)
        val at = frame() ?: return
        val position = last()
        val maxStep = at.geometry.lineHeight * AUTO_SCROLL_MAX_LINES
        val (dx, dy) = AutoScroll.step(position.x, position.y, area.width.toFloat(), area.height.toFloat(), maxStep)
        if (dx == 0f && dy == 0f) return
        model.viewport.scrollBy(dx, dy)
        frame()?.let { model.pointerDragged(it.offsetAt(position.x, position.y)) }
    }
}

/** Returns the physical modifiers of a pointer event. */
internal fun PointerKeyboardModifiers.toKeyModifiers(): Set<KeyModifier> = buildSet {
    if (isShiftPressed) add(KeyModifier.SHIFT)
    if (isCtrlPressed) add(KeyModifier.CTRL)
    if (isAltPressed) add(KeyModifier.ALT)
    if (isMetaPressed) add(KeyModifier.META)
}

/** The number of lines scrolled per wheel notch. */
private const val WHEEL_LINES = 3f

/** The largest step of the automatic drag scrolling per tick, in lines. */
private const val AUTO_SCROLL_MAX_LINES = 2f
