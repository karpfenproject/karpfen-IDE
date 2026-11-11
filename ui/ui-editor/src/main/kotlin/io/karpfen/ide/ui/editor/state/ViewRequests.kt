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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pending requests of an editor state to its view. Requests are state, not events. So a scroll or focus request
 * made before `CodeEditor` is composed stays pending until the view consumes it. A newer scroll request replaces an
 * older pending one. Use it from the UI thread.
 */
internal class ViewRequests {

    private val scroll = MutableStateFlow<ScrollRequest?>(null)
    private val focus = MutableStateFlow(false)

    /** The pending scroll request, or null. */
    val scrollRequest: StateFlow<ScrollRequest?> = scroll.asStateFlow()

    /** True while a focus request is pending. */
    val focusRequested: StateFlow<Boolean> = focus.asStateFlow()

    /** Replaces the pending scroll request with [request]. */
    fun scrollTo(request: ScrollRequest) {
        scroll.value = request
    }

    /** Clears the pending scroll request if it is still [request]. A newer request stays pending. */
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
