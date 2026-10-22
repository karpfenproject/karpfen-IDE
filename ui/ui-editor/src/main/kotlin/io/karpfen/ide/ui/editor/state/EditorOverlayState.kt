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
 * What the hover tooltip shows for [range]: the [diagnostics] at the position if there are any, otherwise the language
 * [hover]. At least one of them is present.
 */
public data class HoverContent(public val range: TextRange, public val diagnostics: List<Diagnostic>, public val hover: Hover?) {
    init {
        require(diagnostics.isNotEmpty() || hover != null) { "hover content needs diagnostics or a hover" }
    }
}

/**
 * A context-menu request at text [offset], shown at ([x], [y]) px in the coordinate space of the `EditorOverlayHost`
 * (the text area, i.e. the Box the `EditorOverlays.Popups` slot is placed in; neither the gutter nor the header slot
 * is included), so the view reports and the popups place it in the same space.
 */
public data class ContextMenuRequest(public val offset: Int, public val x: Float, public val y: Float)

/**
 * Which editor overlays are open (find bar, go-to-line, hover, context menu). The completion popup's state lives in
 * `CompletionSessionModel`. This is the state the overlay slot (`EditorOverlays`) renders; the editor state opens
 * overlays from gestures and actions, the popups close them. Compose-free and shared by the view and the popups. A
 * concrete class, not an interface: a plain state holder without collaborators is its own fake, so tests use the real
 * one. Like `CodeEditorState` it is a mutable state holder outside the editor model and therefore named `*State`.
 *
 * Find matches are drawn by the view only while [findBar] is open; closing the bar ends the highlights without
 * touching the find query.
 */
public class EditorOverlayState {

    private val findBarState = MutableStateFlow<FindBarMode?>(null)
    private val findFocusState = MutableStateFlow(0L)
    private val goToLineState = MutableStateFlow(false)
    private val hoverState = MutableStateFlow<HoverContent?>(null)
    private val contextMenuState = MutableStateFlow<ContextMenuRequest?>(null)

    /** The open find bar, or null. */
    public val findBar: StateFlow<FindBarMode?> = findBarState.asStateFlow()

    /**
     * Counts [openFind] calls. The find bar focuses its query field and selects the query whenever the value changes,
     * so a second Cmd/Ctrl+F while the bar is open in the same mode (no change of [findBar]) still refocuses it.
     */
    public val findFocusRequests: StateFlow<Long> = findFocusState.asStateFlow()

    /** True while the go-to-line popup is open. */
    public val goToLine: StateFlow<Boolean> = goToLineState.asStateFlow()

    /** The hover tooltip content, or null. */
    public val hover: StateFlow<HoverContent?> = hoverState.asStateFlow()

    /** The open context menu, or null. */
    public val contextMenu: StateFlow<ContextMenuRequest?> = contextMenuState.asStateFlow()

    /**
     * Opens the find bar in [mode] (switches mode if already open) and requests focus for its query field
     * ([findFocusRequests]). Seeding the query (e.g. from a one-line selection) is done before through
     * `FindModel.setQuery`.
     */
    public fun openFind(mode: FindBarMode) {
        findBarState.value = mode
        findFocusState.value = findFocusState.value + 1
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
