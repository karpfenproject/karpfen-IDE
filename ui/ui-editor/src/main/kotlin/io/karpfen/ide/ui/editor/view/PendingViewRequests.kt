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
package io.karpfen.ide.ui.editor.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * Applies the pending view requests of the editor state once `CodeEditor` is composed and laid out. Requests are state,
 * so requests made before the composition are not lost.
 *
 * A scroll request reveals its offset through `EditorViewport.reveal`, centred if the request asks for it. This
 * happens as soon as the text area has a size and the drawn geometry belongs to the current text, because the offset
 * refers to the current text. Then the request is consumed.
 *
 * A focus request focuses the input field of the text area and is then consumed.
 */
@Composable
internal fun PendingViewRequests(render: EditorRenderState, focusRequester: FocusRequester) {
    val editor = render.editor
    val scrollRequest by render.scrollRequest
    val focusRequested by editor.focusRequested.collectAsState()
    val geometry = render.geometry.value
    val laidOut by remember(render) { derivedStateOf { render.viewport.value.let { it.lineHeight > 0f && it.viewportHeight > 0f } } }

    LaunchedEffect(scrollRequest, laidOut, geometry) {
        val request = scrollRequest ?: return@LaunchedEffect
        if (!laidOut || geometry.snapshot.version != editor.snapshot.value.version) return@LaunchedEffect
        val offset = request.offset.coerceIn(0, geometry.snapshot.length)
        val x = geometry.xOf(offset)
        val top = geometry.lineTop(geometry.lineOf(offset))
        editor.viewport.reveal(x, top, x + geometry.charWidth, top + geometry.lineHeight, request.center)
        editor.consumeScrollRequest(request)
    }

    LaunchedEffect(focusRequested) {
        if (!focusRequested) return@LaunchedEffect
        focusRequester.requestFocus()
        editor.consumeFocusRequest()
    }
}
