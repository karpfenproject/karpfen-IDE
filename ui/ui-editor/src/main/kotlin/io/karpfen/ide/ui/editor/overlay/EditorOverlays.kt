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
 * The slot API through which `CodeEditor` (package `view`) hosts the editor popups (package `popup`) without either
 * package depending on the other: the view and the popups meet only here, which the architecture tests of the editor's
 * layering check.
 *
 * `CodeEditor` calls [Header] above the text area (in a Column; the find/replace bar goes here) and [Popups] in a Box
 * layered over the text area, filling it (completion list, go-to-line, hover tooltip, context menu, positioned with
 * [EditorOverlayHost.boundsOf] / [EditorOverlayHost.caretBounds]). Both slots read what to show from the host's
 * [EditorPopupModel] (`completion.state`, `overlays.*`, `find.state`) and report intents to it. The workbench passes
 * the popups implementation (`io.karpfen.ide.ui.editor.popup.EditorPopups`); tests and embedded editors may pass
 * [None].
 *
 * Keyboard: while the completion list is open the text area keeps focus and routes keys through
 * `CompletionSessionModel.handleKey`; the find bar and go-to-line popup own focus while open and return it with
 * `EditorPopupModel.requestFocus()`. Navigation is not a popup concern: "Go to Definition" in the context menu runs
 * the editor action of that id.
 */
@Stable
public interface EditorOverlays {

    /** Content above the text area (find/replace bar); emits nothing when no header overlay is open. */
    @Composable
    public fun Header(host: EditorOverlayHost)

    /** Popups over the text area; emits nothing when no popup is open. */
    @Composable
    public fun Popups(host: EditorOverlayHost)

    /** The empty implementation. */
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
 * What `CodeEditor` offers to its overlays. Geometry is in the coordinate space of the text area (the Box that
 * [EditorOverlays.Popups] is placed in), in pixels.
 */
@Stable
public interface EditorOverlayHost {

    /** What the popups may use of the editor (models, overlay state, actions). */
    public val state: EditorPopupModel

    /** The editor's colours and styles. */
    public val theme: EditorTheme

    /** The editor's monospace text style (popups showing code use it). */
    public val textStyle: TextStyle

    /** The primary caret's rectangle, or null if it is scrolled out of view. */
    public val caretBounds: Rect?

    /**
     * True if the primary caret is out of view and stays there — the user scrolled it away; false while it is visible
     * or about to be revealed (a pending scroll request, e.g. the caret reveal after a keystroke or an explicit
     * completion request). [caretBounds] describes the scroll position of the last frame, so it may still be null for a
     * caret that the next frames show; popups anchored at the caret wait then and close only for a caret scrolled away.
     * The default knows of no pending reveal.
     */
    public val isCaretScrolledAway: Boolean get() = caretBounds == null

    /** The rectangle of the character at [offset], or null if that line is not laid out (off-screen). */
    public fun boundsOf(offset: Int): Rect?
}
