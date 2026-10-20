package io.karpfen.ide.ui.editor.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The hover tooltip policy ([EditorViewModel.pointerHover], spec §7.2), Compose-free and on the editor's clock: after
 * the pointer rested on one offset for [delay] (a coroutine delay in [scope], so tests use virtual time), the content
 * from [contentAt] is shown through [overlays]. Moving to an offset outside the shown content's range hides the tooltip
 * and restarts the delay; leaving the text ([pointerAt] with null) and [hide] (typing, keys, presses, focus loss) hide
 * it. Nothing is shown while the context menu is open. Used from the UI thread.
 */
internal class HoverController(
    private val scope: CoroutineScope,
    private val overlays: EditorOverlayState,
    private val delay: Duration,
    private val contentAt: suspend (offset: Int) -> HoverContent?,
) {

    private var restingAt: Int? = null
    private var pending: Job? = null

    /** The pointer rests over text [offset], or left the text (null). */
    fun pointerAt(offset: Int?) {
        if (offset == restingAt) return
        val shown = overlays.hover.value
        if (offset != null && shown != null && shown.range.containsInclusive(offset)) {
            restingAt = offset
            return
        }
        hide()
        restingAt = offset ?: return
        pending = scope.launch {
            delay(delay)
            val content = contentAt(offset)
            if (content != null && restingAt == offset && overlays.contextMenu.value == null) overlays.showHover(content)
        }
    }

    /** Hides the tooltip and forgets the resting position (the next rest starts a new delay). */
    fun hide() {
        pending?.cancel()
        pending = null
        restingAt = null
        overlays.hideHover()
    }
}
