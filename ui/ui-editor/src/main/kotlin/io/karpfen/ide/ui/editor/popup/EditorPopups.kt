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
 * The editor popups, plugged into `CodeEditor` through the [EditorOverlays] slots.
 *
 * [Header] shows the find and replace bar while `overlays.findBar` is open. The bar focuses its query field and selects
 * the query whenever `overlays.findFocusRequests` changes.
 *
 * [Popups] shows the completion list while `completion.state` is open. The list is anchored at the caret and shows at
 * most 12 rows. Each row has a kind badge, the label and the detail, and rows can be selected with the mouse. [Popups]
 * also shows the go-to-line popup (`EditorPopupModel.goToLine`), the hover tooltip and the context menu. Each context
 * menu entry runs its action by id and is greyed out unless `EditorPopupModel.isEnabled` is true.
 *
 * Each popup composable renders immutable state and reports intents to the host's `EditorPopupModel`. None of them
 * holds editing logic. Popups that own focus give it back with `EditorPopupModel.requestFocus()`.
 *
 * This class only composes the slots, which live in `PopupSlots.kt`. Each slot reads what its popup shows from the
 * host, which means the flows of the `EditorPopupModel` and the host's geometry. A slot emits nothing while its popup
 * is closed, and it wires the stateless popup composable to the intents. Positions are in the coordinate space of the
 * host, which is the text area as [EditorOverlayHost] defines it. [PopupIntents] turns gestures into model calls,
 * [PopupPlacement] computes positions, and [FindBarFields] holds the hoisted text fields of the find bar. The class has
 * no state itself, so one instance can serve every editor of a workbench.
 */
public class EditorPopups(
    /** The entries of the context menu. The default entries are Cut, Copy, Paste, Go to Definition and Toggle Comment. */
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

    /** Holds the default entries. */
    public companion object {
        /** The default context menu. Its titles come from [EditorActionTitles], which the window menu shares. */
        public val DEFAULT_CONTEXT_MENU: List<EditorContextMenuItem> = listOf(
            EditorActionIds.CUT,
            EditorActionIds.COPY,
            EditorActionIds.PASTE,
            EditorActionIds.GO_TO_DEFINITION,
            EditorActionIds.TOGGLE_COMMENT,
        ).map { EditorContextMenuItem(it) }
    }
}

/** A context menu entry that runs the editor action [actionId]. It shows [title], which defaults to the shared title of the action. */
public data class EditorContextMenuItem(
    public val actionId: String,
    public val title: String = EditorActionTitles.of(actionId),
)
