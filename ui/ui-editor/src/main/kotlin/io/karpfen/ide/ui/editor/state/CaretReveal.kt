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

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.caret.CaretModel

/**
 * Reveals the caret after actions and typing, so the caret stays visible with the 3-line margin of the viewport. It
 * sets a [ScrollRequest] for the primary caret.
 *
 * The request is centred when the caret is off-screen by more than one viewport height. That is a jump, as after find
 * next, go to definition or a move to the document end. Otherwise the request is minimal. So arrow keys and Page Up or
 * Page Down at the edge of the viewport scroll just as much as needed instead of centring the view again. Without known
 * geometry the request is minimal, and the view applies it once it is laid out.
 */
internal class CaretReveal(
    private val text: EditableText,
    private val caret: CaretModel,
    private val viewport: EditorViewport,
    private val requests: ViewRequests,
) {

    /** Requests that the primary caret becomes visible. */
    fun revealPrimary() {
        val offset = caret.primary.position
        val line = text.snapshot.value.lineIndex.lineOf(offset)
        requests.scrollTo(ScrollRequest(offset, center = isJump(line)))
    }

    /**
     * Whether [line] lies more than one viewport height above or below the visible lines. Returns false while the
     * geometry is unknown.
     */
    private fun isJump(line: Int): Boolean {
        val geometry = viewport.state.value
        if (geometry.lineHeight <= 0f || geometry.viewportHeight <= 0f) return false
        val top = geometry.lineTop(line)
        return top + geometry.lineHeight < geometry.scrollY - geometry.viewportHeight ||
            top > geometry.scrollY + 2 * geometry.viewportHeight
    }
}
