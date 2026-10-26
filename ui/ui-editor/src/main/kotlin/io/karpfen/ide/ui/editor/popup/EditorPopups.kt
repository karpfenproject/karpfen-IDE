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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.overlay.EditorOverlays

/**
 * The editor popups, plugged into `CodeEditor` through the [EditorOverlays] slots:
 * - [Header]: the find/replace bar while `overlays.findBar` is open; it focuses its query field and selects the query
 *   whenever `overlays.findFocusRequests` changes;
 * - [Popups]: the completion list while `completion.state` is open (anchored at the caret, ≤ 12 rows, kind badge,
 *   label, detail; mouse selection), the go-to-line popup (`EditorPopupModel.goToLine`), the hover tooltip and the
 *   context menu (entries run their action by id, greyed out unless `EditorPopupModel.isEnabled`).
 *
 * Each popup composable renders immutable state and reports intents to the host's `EditorPopupModel`; none holds
 * editing logic. Popups that own focus return it with `EditorPopupModel.requestFocus()`.
 *
 * Structure: this class only composes the slots (`PopupSlots.kt`). Each slot reads what its popup shows from the host
 * (the `EditorPopupModel`'s flows and the host's geometry), emits nothing while its popup is closed, and wires the
 * stateless popup composable to the intents; positions are in the host's coordinate space (the text area), as
 * [EditorOverlayHost] defines. Gestures become model calls in [PopupIntents], placement is [PopupPlacement], and the
 * find bar's text fields are hoisted in [FindBarFields]. Stateless itself, so one instance can serve every editor of a
 * workbench.
 */
public class EditorPopups(
    /** The context-menu entries by action id; by default Cut, Copy, Paste, Go to Definition, Toggle Comment. */
    public val contextMenuActions: List<EditorContextMenuItem> = DEFAULT_CONTEXT_MENU,
) : EditorOverlays {

    @Composable
    override fun Header(host: EditorOverlayHost) {
        val intents = remember(host.state) { PopupIntents(host.state) }
        FindBarSlot(host, intents)
    }

    @Composable
    override fun Popups(host: EditorOverlayHost) {
        val intents = remember(host.state) { PopupIntents(host.state) }
        CompletionSlot(host, intents)
        HoverSlot(host, intents)
        GoToLineSlot(host, intents)
        ContextMenuSlot(host, contextMenuActions, intents)
    }

    /** The default entries. */
    public companion object {
        /** The default context menu (titles from [EditorActionTitles], shared with the window menu). */
        public val DEFAULT_CONTEXT_MENU: List<EditorContextMenuItem> = listOf(
            EditorActionIds.CUT,
            EditorActionIds.COPY,
            EditorActionIds.PASTE,
            EditorActionIds.GO_TO_DEFINITION,
            EditorActionIds.TOGGLE_COMMENT,
        ).map { EditorContextMenuItem(it) }
    }
}

/** A context-menu entry running the editor action [actionId], labelled [title] (default: its shared title). */
public data class EditorContextMenuItem(
    public val actionId: String,
    public val title: String = EditorActionTitles.of(actionId),
)
