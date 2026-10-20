package io.karpfen.ide.ui.editor.view.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * The mouse input of the text area (spec §7.2): hit-testing plus the drag auto-scroll timer only, everything else is a
 * Compose-free intent of [model] (decision 36 of the work-package plan), with positions in text-area coordinates (the
 * `EditorOverlayHost` space):
 * - primary press → `pointerPressed(offset, clickCount, modifiers)` (clicks counted by [ClickCounter]), drag →
 *   `pointerDragged(offset)` with auto-scroll while the pointer is outside the area, release → `pointerReleased()`;
 * - secondary press → `openContextMenuAt(offset, x, y)`;
 * - movement without buttons → `pointerHover(character offset or null)`, leaving the area → `pointerHover(null)`;
 * - the mouse wheel scrolls the viewport (Shift+wheel horizontally, [wheelScroll]).
 *
 * Presses are taken in the initial pass and consumed, so the proxy text field below them never reacts to the mouse.
 *
 * @param frame the text area at the current scroll position (null before the first layout); read at every event.
 */
internal fun Modifier.textAreaPointerInput(model: EditorViewModel, frame: () -> TextAreaFrame?): Modifier =
    pointerInput(model) {
        val clicks = ClickCounter(viewConfiguration.doubleTapTimeoutMillis, viewConfiguration.touchSlop)
        coroutineScope {
            val timers: CoroutineScope = this
            awaitEachGesture {
                // Any button: awaitFirstDown only reports the primary mouse button on desktop.
                var event = awaitPointerEvent(PointerEventPass.Initial)
                while (event.changes.none { it.changedToDownIgnoreConsumed() }) event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.first { it.changedToDownIgnoreConsumed() }
                val at = frame() ?: return@awaitEachGesture
                down.consume()
                val position = down.position
                if (event.buttons.isSecondaryPressed) {
                    model.openContextMenuAt(at.offsetAt(position.x, position.y), position.x, position.y)
                    return@awaitEachGesture
                }
                if (!event.buttons.isPrimaryPressed) return@awaitEachGesture
                val count = clicks.press(down.uptimeMillis, position.x, position.y)
                model.pointerPressed(at.offsetAt(position.x, position.y), count, event.keyboardModifiers.toKeyModifiers())
                var last = position
                var autoScroll: Job? = null
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            last = change.position
                            frame()?.let { model.pointerDragged(it.offsetAt(last.x, last.y)) }
                            val outside = last.x < 0f || last.y < 0f || last.x > size.width || last.y > size.height
                            if (outside && autoScroll?.isActive != true) {
                                autoScroll = timers.launch { autoScroll(model, frame, { last }, size.width.toFloat(), size.height.toFloat()) }
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
 * The mouse wheel scrolls [viewport] by [WHEEL_LINES] lines of [lineHeight] per notch (Shift+wheel horizontally); used
 * by the text area and the gutter.
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

/** Scrolls while the drag position [last] is outside the area, extending the selection to the point after each step. */
private suspend fun CoroutineScope.autoScroll(
    model: EditorViewModel,
    frame: () -> TextAreaFrame?,
    last: () -> Offset,
    width: Float,
    height: Float,
) {
    while (isActive) {
        delay(AutoScroll.INTERVAL_MILLIS)
        val at = frame() ?: return
        val position = last()
        val (dx, dy) = AutoScroll.step(position.x, position.y, width, height, at.geometry.lineHeight * AUTO_SCROLL_MAX_LINES)
        if (dx == 0f && dy == 0f) return
        model.viewport.scrollBy(dx, dy)
        frame()?.let { model.pointerDragged(it.offsetAt(position.x, position.y)) }
    }
}

/** The physical modifiers of a pointer event. */
internal fun PointerKeyboardModifiers.toKeyModifiers(): Set<KeyModifier> = buildSet {
    if (isShiftPressed) add(KeyModifier.SHIFT)
    if (isCtrlPressed) add(KeyModifier.CTRL)
    if (isAltPressed) add(KeyModifier.ALT)
    if (isMetaPressed) add(KeyModifier.META)
}

/** Lines scrolled per wheel notch. */
private const val WHEEL_LINES = 3f

/** The largest auto-scroll step per tick, in lines. */
private const val AUTO_SCROLL_MAX_LINES = 2f
