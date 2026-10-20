package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.overlay.EditorOverlays

/**
 * The editor popups (spec §7.2), plugged into `CodeEditor` through the [EditorOverlays] slots:
 * - [Header]: the find/replace bar while `overlays.findBar` is open;
 * - [Popups]: the completion list while `completion.state` is open (anchored at the caret, ≤ 12 rows, kind badge,
 *   label, detail; mouse selection), the go-to-line popup (`EditorPopupModel.goToLine`), the hover tooltip and the
 *   context menu (entries run their action by id, greyed out unless `EditorPopupModel.isEnabled`).
 *
 * Each popup composable renders immutable state and reports intents to the host's `EditorPopupModel`; none holds
 * editing logic. Popups that own focus return it with `EditorPopupModel.requestFocus()`.
 */
public class EditorPopups(
    /** Entries of the editor context menu, by action id (spec §7.2: Cut, Copy, Paste, Go to Definition, Toggle Comment). */
    public val contextMenuActions: List<EditorContextMenuItem> = DEFAULT_CONTEXT_MENU,
) : EditorOverlays {

    @Composable
    override fun Header(host: EditorOverlayHost): Unit = TODO("WP-EDITOR-POPUPS: find/replace bar slot")

    @Composable
    override fun Popups(host: EditorOverlayHost): Unit = TODO("WP-EDITOR-POPUPS: completion, go-to-line, hover, context menu")

    public companion object {
        /** The default context menu. */
        public val DEFAULT_CONTEXT_MENU: List<EditorContextMenuItem> = listOf(
            EditorContextMenuItem(EditorActionIds.CUT, "Cut"),
            EditorContextMenuItem(EditorActionIds.COPY, "Copy"),
            EditorContextMenuItem(EditorActionIds.PASTE, "Paste"),
            EditorContextMenuItem(EditorActionIds.GO_TO_DEFINITION, "Go to Definition"),
            EditorContextMenuItem(EditorActionIds.TOGGLE_COMMENT, "Toggle Comment"),
        )
    }
}

/** A context-menu entry running the editor action [actionId]. */
public data class EditorContextMenuItem(public val actionId: String, public val title: String)
