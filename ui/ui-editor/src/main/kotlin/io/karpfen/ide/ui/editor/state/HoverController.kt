package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The hover tooltip policy ([EditorViewModel.pointerHover], [EditorPopupModel.pointerOverTooltip], spec §7.2),
 * Compose-free and on the editor's clock: after the pointer rested on one offset for [delay] (a coroutine delay in
 * [scope], so tests use virtual time), the content from [contentAt] is shown through [overlays].
 *
 * The tooltip stays while the pointer is on its range or on the tooltip itself, so its long content can be scrolled.
 * Once the pointer left both — to another offset, out of the text, off the tooltip — the tooltip is hidden after
 * [hideGrace] unless the pointer comes back in time; the grace covers the way from the text onto the tooltip, which
 * leaves the text first. Moving to another offset also restarts the delay there. [hide] (typing, keys, presses, focus
 * loss) hides it at once. Nothing is shown while the context menu is open. Used from the UI thread.
 *
 * The tooltip follows the [text]: its range and content describe the text they were computed for, so every change of
 * the text — typing, commands from menus, find and replace, completion accepted by mouse, external reloads — hides
 * it and drops a lookup in progress, like [hide]; the pointer rests again to see the new text's content.
 */
internal class HoverController(
    private val scope: CoroutineScope,
    private val overlays: EditorOverlayState,
    text: StateFlow<TextSnapshot>,
    private val delay: Duration,
    private val hideGrace: Duration,
    private val contentAt: suspend (offset: Int) -> HoverContent?,
) {

    private var restingAt: Int? = null
    private var pending: Job? = null
    private var hiding: Job? = null
    private var onTooltip = false

    init {
        // Every snapshot after the one current now is a change of the text (snapshots are never reused).
        val initial = text.value
        scope.launch { text.filter { it !== initial }.collect { hide() } }
    }

    /** The pointer rests over text [offset], or left the text (null). */
    fun pointerAt(offset: Int?) {
        // The text area reports the pointer, so it is not on the tooltip (which lies over the text area).
        if (offset != null) onTooltip = false
        if (offset == restingAt) return
        restingAt = offset
        cancelPending()
        val shown = overlays.hover.value
        if (offset != null && shown != null && shown.range.containsInclusive(offset)) {
            keepShown()
            return
        }
        if (!onTooltip) hideLater()
        if (offset != null) {
            pending = scope.launch {
                delay(delay)
                val content = contentAt(offset)
                if (content != null && restingAt == offset && overlays.contextMenu.value == null) show(content)
            }
        }
    }

    /** The pointer moved onto ([on] true) or off the shown tooltip. */
    fun pointerOnTooltip(on: Boolean) {
        onTooltip = on
        if (on) {
            restingAt = null
            cancelPending()
            keepShown()
        } else {
            hideLater()
        }
    }

    /** Hides the tooltip at once and forgets the resting position (the next rest starts a new delay). */
    fun hide() {
        cancelPending()
        keepShown()
        restingAt = null
        onTooltip = false
        overlays.hideHover()
    }

    private fun show(content: HoverContent) {
        keepShown()
        onTooltip = false
        overlays.showHover(content)
    }

    private fun cancelPending() {
        pending?.cancel()
        pending = null
    }

    /** Cancels a scheduled [hideLater]. */
    private fun keepShown() {
        hiding?.cancel()
        hiding = null
    }

    /** Hides a shown tooltip after [hideGrace] (unless [keepShown] comes first). */
    private fun hideLater() {
        if (overlays.hover.value == null || hiding != null) return
        hiding = scope.launch {
            delay(hideGrace)
            hiding = null
            overlays.hideHover()
        }
    }

    companion object {
        /** How long the tooltip stays after the pointer left its range and the tooltip. */
        val HIDE_GRACE: Duration = 300.milliseconds
    }
}
