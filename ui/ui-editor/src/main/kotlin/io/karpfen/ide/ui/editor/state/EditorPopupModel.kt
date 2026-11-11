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

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.find.FindModel
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import kotlinx.coroutines.flow.StateFlow

/**
 * The part of an editor that the editor popups may use. The popups are the find and replace bar, the completion list,
 * the go-to-line popup, the hover tooltip and the context menu. This interface follows interface segregation. It offers
 * the models the popups render and drive, the overlay state, and action execution and enablement. [CodeEditorState]
 * extends it, next to `EditorViewModel` and `EditorHandle`. Popup tests fake only this interface and use the real
 * models, which do not need Compose. Use it from the UI thread.
 *
 * The find bar drives [find] directly with `setQuery`, `next`, `previous` and the replace functions. `FindModel` selects
 * matches through the editor's executor, so the editor reveals them, as described on `CodeEditorState`.
 */
public interface EditorPopupModel {

    /** The current text. Go-to-line reads the line count and the current line from it. */
    public val snapshot: StateFlow<TextSnapshot>

    /** The carets and the selection. */
    public val caret: CaretModel

    /** Find and replace. */
    public val find: FindModel

    /** The completion session. */
    public val completion: CompletionSessionModel

    /** The open overlays: the find bar, go-to-line, the hover tooltip and the context menu. */
    public val overlays: EditorOverlayState

    /**
     * The ids of the registered actions that are enabled now, which means `EditorAction.isEnabled` is true in the
     * editor's action context. The set is computed again whenever the carets, the text or the undo history change. The
     * context menu greys out the other actions. The workbench derives `Command.isEnabled` of forwarded editor commands
     * from it.
     *
     * `isEnabled` depends only on those inputs. So actions that depend on the clipboard, such as Paste, are always
     * enabled, and the enablement never goes stale when another application changes the clipboard.
     */
    public val enabledActions: StateFlow<Set<String>>

    /** True if the action [actionId] is registered and enabled now. */
    public fun isEnabled(actionId: String): Boolean = actionId in enabledActions.value

    /** Runs the registered action [actionId] and returns whether it did something. */
    public fun execute(actionId: String): Boolean

    /**
     * Moves the primary caret to [query], clamped to the existing lines and columns. It scrolls the caret to the
     * centre, closes the go-to-line popup and requests focus for the text area.
     */
    public fun goToLine(query: GoToLineQuery)

    /**
     * Asks the view to focus the text area, for example after Escape in the find bar. The request is held in
     * `CodeEditorState.focusRequested` until the view consumes it.
     */
    public fun requestFocus()

    /**
     * Reports that the pointer moved onto the hover tooltip (`overlays.hover`) when [over] is true, or off it when
     * [over] is false. The tooltip stays while the pointer is on it, so its content can be scrolled. It is hidden
     * shortly after the pointer left both the tooltip and the hovered text, as described on
     * `EditorViewModel.pointerHover`.
     */
    public fun pointerOverTooltip(over: Boolean)
}
