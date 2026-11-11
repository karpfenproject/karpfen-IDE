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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.text.TextRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which find bar is open. */
public enum class FindBarMode {
    /** Find only, opened with primary+F. */
    FIND,

    /** Find and replace, opened with primary+R. */
    REPLACE,
}

/**
 * What the hover tooltip shows for [range]. It shows the [diagnostics] at the position if there are any, and the
 * language [hover] otherwise. At least one of the two is present.
 */
public data class HoverContent(public val range: TextRange, public val diagnostics: List<Diagnostic>, public val hover: Hover?) {
    init {
        require(diagnostics.isNotEmpty() || hover != null) { "hover content needs diagnostics or a hover" }
    }
}

/**
 * A request to open the context menu at text [offset]. The menu is shown at [x] and [y], in pixels, in the coordinate
 * space of the `EditorOverlayHost`. That space is the text area, which is the Box the `EditorOverlays.Popups` slot is
 * placed in. It includes neither the gutter nor the header slot. So the view reports the position and the popups place
 * the menu in the same space.
 */
public data class ContextMenuRequest(public val offset: Int, public val x: Float, public val y: Float)

/**
 * Which editor overlays are open: the find bar, go-to-line, the hover tooltip and the context menu. The state of the
 * completion popup lives in `CompletionSessionModel` instead.
 *
 * The overlay slot `EditorOverlays` renders this state. The editor state opens overlays from gestures and actions, and
 * the popups close them. The class does not need Compose and is shared by the view and the popups. It is a concrete
 * class, not an interface, because a plain state holder without collaborators is its own fake. So tests use the real
 * one. Like `CodeEditorState`, it is a mutable state holder outside the editor model, and that is why its name ends in
 * `State`.
 *
 * The view draws find matches only while [findBar] is open. Closing the bar ends the highlights without touching the
 * find query.
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
     * Counts the calls of [openFind]. The find bar focuses its query field and selects the query whenever this value
     * changes. So a second primary+F while the bar is open in the same mode still focuses the field again, although
     * [findBar] does not change.
     */
    public val findFocusRequests: StateFlow<Long> = findFocusState.asStateFlow()

    /** True while the go-to-line popup is open. */
    public val goToLine: StateFlow<Boolean> = goToLineState.asStateFlow()

    /** The hover tooltip content, or null. */
    public val hover: StateFlow<HoverContent?> = hoverState.asStateFlow()

    /** The open context menu, or null. */
    public val contextMenu: StateFlow<ContextMenuRequest?> = contextMenuState.asStateFlow()

    /**
     * Opens the find bar in [mode], or switches its mode if it is already open. It also requests focus for the query
     * field through [findFocusRequests]. Seeding the query, for example from a selection on one line, happens before
     * this call through `FindModel.setQuery`.
     */
    public fun openFind(mode: FindBarMode) {
        findBarState.value = mode
        findFocusState.value = findFocusState.value + 1
    }

    /** Closes the find bar. */
    public fun closeFind() {
        findBarState.value = null
    }

    /** Opens the go-to-line popup and closes the hover tooltip and the context menu. */
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

    /** Opens the context menu for [request] and hides the hover tooltip. */
    public fun openContextMenu(request: ContextMenuRequest) {
        hoverState.value = null
        contextMenuState.value = request
    }

    /** Closes the context menu. */
    public fun closeContextMenu() {
        contextMenuState.value = null
    }

    /** Closes every overlay, for example when the editor is disposed. */
    public fun closeAll() {
        findBarState.value = null
        goToLineState.value = false
        hoverState.value = null
        contextMenuState.value = null
    }
}
