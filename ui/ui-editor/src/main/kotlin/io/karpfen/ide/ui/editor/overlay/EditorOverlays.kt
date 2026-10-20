package io.karpfen.ide.ui.editor.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import io.karpfen.ide.ui.editor.state.EditorPopupModel
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The slot API through which `CodeEditor` (WP-EDITOR-VIEW) hosts the editor popups (WP-EDITOR-POPUPS) without either
 * package depending on the other's files (spec §7.2: popups are separate files hosted by `CodeEditor`).
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

    /** The rectangle of the character at [offset], or null if that line is not laid out (off-screen). */
    public fun boundsOf(offset: Int): Rect?
}
