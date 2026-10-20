package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.text.TextRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which find bar is open. */
public enum class FindBarMode {
    /** Find only (Cmd/Ctrl+F). */
    FIND,

    /** Find and replace (Cmd/Ctrl+R). */
    REPLACE,
}

/**
 * What the hover tooltip shows for [range] (spec §7.2): the [diagnostics] at the position if there are any, otherwise
 * the language [hover]. At least one of them is present.
 */
public data class HoverContent(public val range: TextRange, public val diagnostics: List<Diagnostic>, public val hover: Hover?) {
    init {
        require(diagnostics.isNotEmpty() || hover != null) { "hover content needs diagnostics or a hover" }
    }
}

/** A context-menu request at text [offset], shown at ([x], [y]) px in the editor's coordinate space. */
public data class ContextMenuRequest(public val offset: Int, public val x: Float, public val y: Float)

/**
 * Which editor overlays are open (find bar, go-to-line, hover, context menu). The completion popup's state lives in
 * `CompletionSessionModel`. This is the state the overlay slot (`EditorOverlays`) renders; the view opens overlays from
 * gestures and actions, the popups close them. Compose-free; complete in the contracts step (shared by WP-EDITOR-VIEW
 * and WP-EDITOR-POPUPS).
 */
public class EditorOverlayController {

    private val findBarState = MutableStateFlow<FindBarMode?>(null)
    private val goToLineState = MutableStateFlow(false)
    private val hoverState = MutableStateFlow<HoverContent?>(null)
    private val contextMenuState = MutableStateFlow<ContextMenuRequest?>(null)

    /** The open find bar, or null. */
    public val findBar: StateFlow<FindBarMode?> = findBarState.asStateFlow()

    /** True while the go-to-line popup is open. */
    public val goToLine: StateFlow<Boolean> = goToLineState.asStateFlow()

    /** The hover tooltip content, or null. */
    public val hover: StateFlow<HoverContent?> = hoverState.asStateFlow()

    /** The open context menu, or null. */
    public val contextMenu: StateFlow<ContextMenuRequest?> = contextMenuState.asStateFlow()

    /** Opens the find bar in [mode] (switches mode if already open). */
    public fun openFind(mode: FindBarMode) {
        findBarState.value = mode
    }

    /** Closes the find bar. */
    public fun closeFind() {
        findBarState.value = null
    }

    /** Opens the go-to-line popup (closing hover and context menu). */
    public fun openGoToLine() {
        hoverState.value = null
        contextMenuState.value = null
        goToLineState.value = true
    }

    /** Closes the go-to-line popup. */
    public fun closeGoToLine() {
        goToLineState.value = false
    }

    /** Shows [content] in the hover tooltip. */
    public fun showHover(content: HoverContent) {
        hoverState.value = content
    }

    /** Hides the hover tooltip. */
    public fun hideHover() {
        hoverState.value = null
    }

    /** Opens the context menu for [request] (hiding the hover tooltip). */
    public fun openContextMenu(request: ContextMenuRequest) {
        hoverState.value = null
        contextMenuState.value = request
    }

    /** Closes the context menu. */
    public fun closeContextMenu() {
        contextMenuState.value = null
    }

    /** Closes every overlay (e.g. when the editor is disposed). */
    public fun closeAll() {
        findBarState.value = null
        goToLineState.value = false
        hoverState.value = null
        contextMenuState.value = null
    }
}
