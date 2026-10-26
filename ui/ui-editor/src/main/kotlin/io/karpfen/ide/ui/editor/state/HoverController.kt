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
 * The hover tooltip policy ([EditorViewModel.pointerHover], [EditorPopupModel.pointerOverTooltip]), Compose-free and on
 * the editor's clock: after the pointer rested on one offset for [delay] (a coroutine delay in [scope], so tests use
 * virtual time), the content from [contentAt] is shown through [overlays].
 *
 * The tooltip stays while the pointer is on its range or on the tooltip itself, so its long content can be scrolled.
 * Once the pointer left both — to another offset, out of the text, off the tooltip — the tooltip is hidden after
 * [hideGrace] unless the pointer comes back in time; the grace covers the way from the text onto the tooltip, which
 * leaves the text first. Moving to another offset also restarts the delay there. [hide] (typing, keys, presses, focus
 * loss) hides it at once. Nothing is shown while the context menu is open. Used from the UI thread.
 *
 * The tooltip follows the [text]: its range and content describe the text they were computed for, so every change of
 * the text — typing, commands from menus, find and replace, completion accepted by mouse, external reloads — hides it
 * and drops a lookup in progress, like [hide]; the pointer rests again to see the new text's content. A change is every
 * snapshot other than the one current at construction: snapshots are never reused, so identity tells them apart.
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
     * The pointer rests over text [offset], or left the text (null). An offset also means that the pointer is not on
     * the tooltip: the text area reports it, and the tooltip lies over the text area.
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

    /** Shows [content], cancelling a scheduled hide. */
    private fun show(content: HoverContent) {
        keepShown()
        onTooltip = false
        overlays.showHover(content)
    }

    /** Cancels the lookup waiting for the rest delay or running. */
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

    /** The hide grace. */
    companion object {
        /** How long the tooltip stays after the pointer left its range and the tooltip. */
        val HIDE_GRACE: Duration = 300.milliseconds
    }
}
