package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.find.FindModel
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import kotlinx.coroutines.flow.StateFlow

/**
 * What the editor popups (find/replace bar, completion list, go-to-line, hover tooltip, context menu) may use of an
 * editor (interface segregation, spec §1a): the models they render and drive, the overlay state, action execution and
 * enablement. [CodeEditorState] extends it (next to `EditorViewModel` and `EditorHandle`); popup tests fake only this
 * interface (with the real Compose-free models). Used from the UI thread.
 *
 * The find bar drives [find] directly (`setQuery`, `next`, `previous`, replace); `FindModel` selects matches through the
 * editor's executor, so the editor reveals them (see `CodeEditorState`, Caret reveal).
 */
public interface EditorPopupModel {

    /** The current text (line count and current line for go-to-line). */
    public val snapshot: StateFlow<TextSnapshot>

    /** Carets and selection. */
    public val caret: CaretModel

    /** Find/replace. */
    public val find: FindModel

    /** Completion session. */
    public val completion: CompletionSessionModel

    /** Open overlays (find bar, go-to-line, hover, context menu). */
    public val overlays: EditorOverlayState

    /**
     * Ids of the registered actions that are enabled now (`EditorAction.isEnabled` in the editor's action context),
     * recomputed whenever the carets, the text or the undo history change. The context menu greys out the others; the
     * workbench derives `Command.isEnabled` of forwarded editor commands from it. `isEnabled` depends only on those
     * inputs (see `EditorAction.isEnabled`): clipboard-dependent actions such as Paste are always enabled, so the
     * enablement never goes stale when another application changes the clipboard.
     */
    public val enabledActions: StateFlow<Set<String>>

    /** True if the action [actionId] is registered and enabled now. */
    public fun isEnabled(actionId: String): Boolean = actionId in enabledActions.value

    /** Runs the registered action [actionId]; returns whether it did something. */
    public fun execute(actionId: String): Boolean

    /**
     * Moves the primary caret to [query] (clamped to existing lines and columns), scrolls it to the centre, closes the
     * go-to-line popup and requests focus for the text area.
     */
    public fun goToLine(query: GoToLineQuery)

    /** Asks the view to focus the text area (e.g. Escape in the find bar); see `CodeEditorState.focusRequested`. */
    public fun requestFocus()

    /**
     * The pointer moved onto ([over] true) or off the hover tooltip (`overlays.hover`). The tooltip stays while the
     * pointer is on it, so its content can be scrolled, and is hidden shortly after the pointer left both the tooltip
     * and the hovered text (see `EditorViewModel.pointerHover`).
     */
    public fun pointerOverTooltip(over: Boolean)
}
