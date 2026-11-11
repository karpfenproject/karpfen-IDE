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

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.state.EditorPopupModel
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.view.geometry.TextAreaFrame

/**
 * The [EditorOverlayHost] of `CodeEditor`. It gives geometry in text area coordinates from the current
 * [TextAreaFrame]. Every member reads Compose state: the theme, the style, the geometry and scroll position of the
 * frame, the carets and the pending scroll request. So popups that use it recompose when the caret moves, the text
 * scrolls or a reveal is applied.
 *
 * [isCaretScrolledAway] also checks the current scroll position of the viewport through [liveFrame]. A reveal applied
 * after the last composition shows the caret there before the next frame does.
 *
 * @param frame the text area at the scroll position of the last composition, or null before the first layout.
 * @param liveFrame the text area at the current scroll position of the viewport, or null before the first layout.
 * @param scrollPending true while a scroll request, such as the caret reveal, waits to be applied.
 * @param primaryCaret the offset of the primary caret in the text of the frame.
 * @param caretWidth the width of the caret in pixels.
 */
@Stable
internal class TextAreaOverlayHost(
    override val state: EditorPopupModel,
    private val themeState: State<EditorTheme>,
    private val textStyleState: State<TextStyle>,
    private val frame: () -> TextAreaFrame?,
    private val liveFrame: () -> TextAreaFrame?,
    private val scrollPending: () -> Boolean,
    private val primaryCaret: () -> Int,
    private val caretWidth: Float,
) : EditorOverlayHost {

    override val theme: EditorTheme get() = themeState.value

    override val textStyle: TextStyle get() = textStyleState.value

    override val caretBounds: Rect?
        get() = frame()?.let(::visibleCaret)

    override val isCaretScrolledAway: Boolean
        get() = caretBounds == null && !scrollPending() && liveFrame()?.let(::visibleCaret) == null

    /** Returns the rectangle of the primary caret in [frame], or null if it lies outside the visible area of the frame. */
    private fun visibleCaret(frame: TextAreaFrame): Rect? =
        frame.caretRect(primaryCaret(), caretWidth).takeIf(frame::isVisible)

    override fun boundsOf(offset: Int): Rect? = frame()?.characterRect(offset)
}
