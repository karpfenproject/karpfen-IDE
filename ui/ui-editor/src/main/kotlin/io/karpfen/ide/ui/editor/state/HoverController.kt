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
 * The hover tooltip policy behind [EditorViewModel.pointerHover] and [EditorPopupModel.pointerOverTooltip]. It does
 * not need Compose and runs on the editor's clock. After the pointer rested on one offset for [delay], the content from
 * [contentAt] is shown through [overlays]. The delay is a coroutine delay in [scope], so tests use virtual time.
 *
 * The tooltip stays while the pointer is on its range or on the tooltip itself, so long content can be scrolled. The
 * pointer can leave both by moving to another offset, out of the text or off the tooltip. The tooltip is then hidden
 * after [hideGrace], unless the pointer comes back in time. The grace period covers the way from the text onto the
 * tooltip, because that way leaves the text first. Moving to another offset also starts the delay again there.
 *
 * [hide] hides the tooltip at once. Typing, keys, presses and focus loss call it. Nothing is shown while the context
 * menu is open. Use the class from the UI thread.
 *
 * The tooltip follows [text], because its range and content describe the text they were computed for. So every change
 * of the text hides it and drops a lookup in progress, just like [hide]. Such changes are typing, commands from menus,
 * find and replace, completion accepted by mouse and external reloads. The pointer must rest again to see content for
 * the new text. Every snapshot other than the one that was current at construction counts as a change. Snapshots are
 * never reused, so identity tells them apart.
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
        val initial = text.value
        scope.launch { text.filter { it !== initial }.collect { hide() } }
    }

    /**
     * Reports that the pointer rests over text [offset], or that it left the text when [offset] is null. An offset also
     * means that the pointer is not on the tooltip, because the text area reports it and the tooltip lies over the text
     * area.
     */
    fun pointerAt(offset: Int?) {
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

    /** Reports that the pointer moved onto the shown tooltip when [on] is true, or off it when [on] is false. */
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

    /** Hides the tooltip at once and forgets the resting position, so the next rest starts a new delay. */
    fun hide() {
        cancelPending()
        keepShown()
        restingAt = null
        onTooltip = false
        overlays.hideHover()
    }

    /** Shows [content], cancelling a scheduled hide. */
    private fun show(content: HoverContent) {
        keepShown()
        onTooltip = false
        overlays.showHover(content)
    }

    /** Cancels the lookup that waits for the rest delay or is running. */
    private fun cancelPending() {
        pending?.cancel()
        pending = null
    }

    /** Cancels a scheduled [hideLater]. */
    private fun keepShown() {
        hiding?.cancel()
        hiding = null
    }

    /** Hides a shown tooltip after [hideGrace], unless [keepShown] comes first. */
    private fun hideLater() {
        if (overlays.hover.value == null || hiding != null) return
        hiding = scope.launch {
            delay(hideGrace)
            hiding = null
            overlays.hideHover()
        }
    }

    /** The hide grace. */
    companion object {
        /** How long the tooltip stays after the pointer left its range and the tooltip. */
        val HIDE_GRACE: Duration = 300.milliseconds
    }
}
