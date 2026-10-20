package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditableText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The carets of one editor over an [EditableText] (spec §7.1). Compose-free state holder, used from the UI thread.
 *
 * Mapping through changes (MUST): carets are stamped with the text version they refer to ([CaretSnapshot.version]).
 * Every member first catches up synchronously when the carets are older than the text (lazy mapping with
 * `EditableText.mapOffset`, like `AutoClosedPairs`), so [current], [primary], [mementos] and every movement always act
 * on the current text, also when a change (a reload, an edit by another component) arrived and the collector in the
 * model's scope has not run yet — the scope's dispatcher does not matter. [state] follows the changes for observers
 * (rendering, completion) and may lag by one dispatch. Carets set explicitly after an edit (actions pass their
 * `caretsAfter`) carry the new version and are never mapped twice.
 *
 * Undo coalescing: every explicit caret change ([move], [moveTo], [select], [selectWord], [selectLine], [selectAll])
 * calls `text.undo.breakCoalescing()`, so typing at another place starts a new undo step; [restore] and the mapping
 * through changes do not.
 *
 * Preferred column: [Caret.preferredColumn] is a display column computed with [TabStops] of the factory's
 * `tabWidth()` (the editor passes `EditorSettings.tabDisplayWidth`), the conversion the view and the status bar use too.
 */
public interface CaretModel {

    /** The carets and the version they refer to, for observers; may lag the text by one dispatch (see above). */
    public val state: StateFlow<CaretSnapshot>

    /** The carets mapped to the current text version (catches up synchronously when [state] lags). */
    public val current: CaretSnapshot

    /** Shortcut for `current.primary`. */
    public val primary: Caret get() = current.primary

    /**
     * Moves every caret by [movement]; with [extendSelection] the anchors stay (Shift+movement), otherwise selections
     * collapse. [pageLines] is the page size for PAGE_UP/PAGE_DOWN.
     */
    public fun move(movement: CaretMovement, extendSelection: Boolean, pageLines: Int = 1)

    /** Places the primary caret at [offset] (clamped); with [extendSelection] the anchor stays (Shift+click). */
    public fun moveTo(offset: Int, extendSelection: Boolean = false)

    /** Replaces all carets by one with the given [anchor] and [position] (clamped). */
    public fun select(anchor: Int, position: Int)

    /** Selects the word at [offset] (double-click). */
    public fun selectWord(offset: Int)

    /** Selects [line] including its line break (triple-click). */
    public fun selectLine(line: Int)

    /** Selects the whole text. */
    public fun selectAll()

    /** The carets as undo mementos. */
    public fun mementos(): List<CaretMemento>

    /** Replaces the carets by [mementos], which refer to the current text version (undo/redo, actions). */
    public fun restore(mementos: List<CaretMemento>)
}

/**
 * A [CaretModel] with one caret at offset 0, mapping carets through the changes of [text] in [scope] (collected on the
 * UI thread; tests pass an `UnconfinedTestDispatcher` scope).
 *
 * @param tabWidth columns per tab stop for [Caret.preferredColumn] ([TabStops]), read on every vertical move (the
 *   setting may change at runtime).
 */
public fun CaretModel(
    text: EditableText,
    scope: CoroutineScope,
    tabWidth: () -> Int = { DEFAULT_TAB_WIDTH },
): CaretModel = DefaultCaretModel(text, scope, tabWidth)

internal class DefaultCaretModel(
    private val text: EditableText,
    private val scope: CoroutineScope,
    private val tabWidth: () -> Int,
) : CaretModel {

    override val state: StateFlow<CaretSnapshot>
        get() = TODO("WP-EDITOR-MODEL: caret state")

    override val current: CaretSnapshot
        get() = TODO("WP-EDITOR-MODEL: carets caught up with the text")

    override fun move(movement: CaretMovement, extendSelection: Boolean, pageLines: Int): Unit =
        TODO("WP-EDITOR-MODEL: caret movement")

    override fun moveTo(offset: Int, extendSelection: Boolean): Unit = TODO("WP-EDITOR-MODEL: move to offset")

    override fun select(anchor: Int, position: Int): Unit = TODO("WP-EDITOR-MODEL: select")

    override fun selectWord(offset: Int): Unit = TODO("WP-EDITOR-MODEL: select word")

    override fun selectLine(line: Int): Unit = TODO("WP-EDITOR-MODEL: select line")

    override fun selectAll(): Unit = TODO("WP-EDITOR-MODEL: select all")

    override fun mementos(): List<CaretMemento> = TODO("WP-EDITOR-MODEL: mementos")

    override fun restore(mementos: List<CaretMemento>): Unit = TODO("WP-EDITOR-MODEL: restore")
}
