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
package io.karpfen.ide.ui.editor.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import io.karpfen.ide.ui.editor.state.EditorPopupModel
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The slot API through which `CodeEditor` in package `view` hosts the editor popups in package `popup`. Neither
 * package depends on the other. The view and the popups meet only here, and the architecture tests of the editor's
 * layering check this.
 *
 * `CodeEditor` calls [Header] above the text area, in a Column. The find and replace bar goes there. It calls [Popups]
 * in a Box that lies over the text area and fills it. The completion list, go-to-line, the hover tooltip and the
 * context menu go there, positioned with [EditorOverlayHost.boundsOf] and [EditorOverlayHost.caretBounds]. Both slots
 * read what to show from the host's [EditorPopupModel], such as `completion.state`, the `overlays` flows and
 * `find.state`, and they report intents to it. The workbench passes the popups implementation
 * `io.karpfen.ide.ui.editor.popup.EditorPopups`. Tests and embedded editors may pass [None].
 *
 * While the completion list is open, the text area keeps keyboard focus and routes keys through
 * `CompletionSessionModel.handleKey`. The find bar and the go-to-line popup own focus while they are open and give it
 * back with `EditorPopupModel.requestFocus()`. Navigation is not a concern of the popups. "Go to Definition" in the
 * context menu runs the editor action of that id.
 */
@Stable
public interface EditorOverlays {

    /** The content above the text area, which is the find and replace bar. It emits nothing when no header overlay is open. */
    @Composable
    public fun Header(host: EditorOverlayHost)

    /** The popups over the text area. It emits nothing when no popup is open. */
    @Composable
    public fun Popups(host: EditorOverlayHost)

    /** Holds the empty implementation. */
    public companion object {
        /** No overlays at all. */
        public val None: EditorOverlays = object : EditorOverlays {
            @Composable
            override fun Header(host: EditorOverlayHost) = Unit

            @Composable
            override fun Popups(host: EditorOverlayHost) = Unit
        }
    }
}

/**
 * What `CodeEditor` offers to its overlays. Geometry is given in pixels in the coordinate space of the text area,
 * which is the Box that [EditorOverlays.Popups] is placed in.
 */
@Stable
public interface EditorOverlayHost {

    /** The part of the editor that the popups may use: the models, the overlay state and the actions. */
    public val state: EditorPopupModel

    /** The editor's colours and styles. */
    public val theme: EditorTheme

    /** The monospace text style of the editor. Popups that show code use it. */
    public val textStyle: TextStyle

    /** The rectangle of the primary caret, or null if the caret is scrolled out of view. */
    public val caretBounds: Rect?

    /**
     * True if the primary caret is out of view and stays there, because the user scrolled it away. It is false while
     * the caret is visible or about to be revealed by a pending scroll request. Such a request comes, for example, from
     * the caret reveal after a keystroke or from an explicit completion request.
     *
     * [caretBounds] describes the scroll position of the last frame. So it may still be null for a caret that the next
     * frames show. Popups anchored at the caret then wait, and they close only for a caret that was scrolled away. The
     * default implementation knows of no pending reveal.
     */
    public val isCaretScrolledAway: Boolean get() = caretBounds == null

    /** Returns the rectangle of the character at [offset], or null if its line is not laid out because it is off-screen. */
    public fun boundsOf(offset: Int): Rect?
}
