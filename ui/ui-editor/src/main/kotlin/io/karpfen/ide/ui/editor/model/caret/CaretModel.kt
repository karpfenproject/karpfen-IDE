package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditableText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The carets of one editor over an [EditableText] (spec §7.1). Compose-free state holder, used from the UI thread.
 *
 * Mapping through changes: carets are stamped with the text version they refer to ([CaretSnapshot.version]). Changes
 * the model has not seen yet (e.g. a reload, or edits by another component) are mapped through
 * `EditableText.mapOffset` as they are emitted, but only if the carets predate them; carets set explicitly after an
 * edit (actions pass their `caretsAfter`) carry the new version and are never mapped twice.
 *
 * Undo coalescing: every explicit caret change ([move], [moveTo], [select], [selectWord], [selectLine], [selectAll])
 * calls `text.undo.breakCoalescing()`, so typing at another place starts a new undo step; [restore] and the mapping
 * through changes do not.
 *
 * Preferred column: [Caret.preferredColumn] is a display column with tabs expanded to the factory's `tabWidth()`
 * (the editor passes `EditorSettings.tabDisplayWidth`).
 */
public interface CaretModel {

    /** The current carets and the version they refer to. */
    public val state: StateFlow<CaretSnapshot>

    /** Shortcut for `state.value.primary`. */
    public val primary: Caret get() = state.value.primary

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
 * @param tabWidth columns per tab stop for [Caret.preferredColumn], read on every vertical move (the setting may
 *   change at runtime).
 */
public fun CaretModel(
    text: EditableText,
    scope: CoroutineScope,
    tabWidth: () -> Int = { DEFAULT_TAB_WIDTH },
): CaretModel = DefaultCaretModel(text, scope, tabWidth)

/** Columns per tab stop unless the editor settings say otherwise (spec §7.2). */
public const val DEFAULT_TAB_WIDTH: Int = 4

internal class DefaultCaretModel(
    private val text: EditableText,
    private val scope: CoroutineScope,
    private val tabWidth: () -> Int,
) : CaretModel {

    override val state: StateFlow<CaretSnapshot>
        get() = TODO("WP-EDITOR-MODEL: caret state")

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
