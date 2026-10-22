package io.karpfen.ide.ui.editor.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pending requests of an editor state to its view: requests are *state*, not events, so a scroll or focus request
 * made before `CodeEditor` is composed stays pending until the view consumes it. A newer scroll request replaces an
 * older pending one. Used from the UI thread.
 */
internal class ViewRequests {

    private val scroll = MutableStateFlow<ScrollRequest?>(null)
    private val focus = MutableStateFlow(false)

    /** The pending scroll request, or null. */
    val scrollRequest: StateFlow<ScrollRequest?> = scroll.asStateFlow()

    /** True while a focus request is pending. */
    val focusRequested: StateFlow<Boolean> = focus.asStateFlow()

    /** Replaces the pending scroll request by [request]. */
    fun scrollTo(request: ScrollRequest) {
        scroll.value = request
    }

    /** Clears the pending scroll request if it is still [request] (a newer one stays pending). */
    fun consumeScroll(request: ScrollRequest) {
        scroll.compareAndSet(request, null)
    }

    /** Sets the pending focus request. */
    fun requestFocus() {
        focus.value = true
    }

    /** Clears the pending focus request. */
    fun consumeFocus() {
        focus.value = false
    }
}
