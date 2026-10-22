package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The carets of one editor over an [EditableText]. Compose-free state holder, used from the UI thread.
 *
 * Mapping through changes: carets are stamped with the text version they refer to ([CaretSnapshot.version]).
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

/**
 * Default [CaretModel]: the carets of one editor, version-stamped and caught up lazily (see [CaretModel]).
 *
 * Collaborators: [CaretMotion] computes movements (pure geometry), [WordBoundaries] the word rule; this class owns the
 * caret state, the mapping through changes (`EditableText.mapOffset`, the point mapping of carets) and the undo
 * coalescing breaks of explicit moves. A collector in [scope] follows `text.snapshot` so [state] catches up for
 * observers; `snapshot` is a state flow, so a collector started late still sees the newest version.
 */
internal class DefaultCaretModel(
    private val text: EditableText,
    private val scope: CoroutineScope,
    private val tabWidth: () -> Int,
) : CaretModel {

    private val motion = CaretMotion(tabWidth)
    private val caretState = MutableStateFlow(CaretSnapshot(text.snapshot.value.version, listOf(Caret.at(0))))

    init {
        scope.launch { text.snapshot.collect { catchUp() } }
    }

    override val state: StateFlow<CaretSnapshot> = caretState.asStateFlow()

    override val current: CaretSnapshot
        get() = catchUp()

    override fun move(movement: CaretMovement, extendSelection: Boolean, pageLines: Int) {
        val carets = current.carets
        val snapshot = text.snapshot.value
        place(carets.map { motion.move(snapshot, it, movement, extendSelection, pageLines) })
    }

    override fun moveTo(offset: Int, extendSelection: Boolean) {
        val anchor = current.primary.anchor
        val target = clamp(offset)
        place(listOf(Caret(if (extendSelection) anchor else target, target)))
    }

    override fun select(anchor: Int, position: Int) {
        catchUp()
        place(listOf(Caret(clamp(anchor), clamp(position))))
    }

    override fun selectWord(offset: Int) {
        catchUp()
        val word = WordBoundaries.wordAt(text.snapshot.value.text, clamp(offset))
        place(listOf(Caret(word.start, word.end)))
    }

    override fun selectLine(line: Int) {
        catchUp()
        val snapshot = text.snapshot.value
        val clamped = line.coerceIn(0, snapshot.lineCount - 1)
        val start = snapshot.lineIndex.lineStart(clamped)
        val end = if (clamped + 1 < snapshot.lineCount) snapshot.lineIndex.lineStart(clamped + 1) else snapshot.length
        place(listOf(Caret(start, end)))
    }

    override fun selectAll() {
        catchUp()
        place(listOf(Caret(0, text.snapshot.value.length)))
    }

    override fun mementos(): List<CaretMemento> = current.carets.map(Caret::toMemento)

    override fun restore(mementos: List<CaretMemento>) {
        if (mementos.isEmpty()) return
        val snapshot = text.snapshot.value
        val carets = mementos.map { Caret(clamp(it.anchor, snapshot.length), clamp(it.caret, snapshot.length)) }
        caretState.value = CaretSnapshot(snapshot.version, carets.distinct())
    }

    /** An explicit caret change: ends the typing run and publishes [carets] for the current version. */
    private fun place(carets: List<Caret>) {
        text.undo.breakCoalescing()
        caretState.value = CaretSnapshot(text.snapshot.value.version, carets.distinct())
    }

    /** Maps the carets to the current version if they are older; returns the (now current) carets. */
    private fun catchUp(): CaretSnapshot {
        val stamped = caretState.value
        val target = text.snapshot.value
        if (stamped.version == target.version) return stamped
        val caught = CaretSnapshot(target.version, stamped.carets.map { map(it, stamped.version, target) }.distinct())
        caretState.value = caught
        return caught
    }

    /**
     * [caret] of version [from] in [target]: both ends mapped as points with `Bias.RIGHT` (a caret moves along with
     * text inserted at it), clamped when the history no longer reaches [from]. The preferred column is forgotten.
     */
    private fun map(caret: Caret, from: Long, target: TextSnapshot): Caret {
        fun point(offset: Int): Int {
            val mapped = if (from < target.version) text.mapOffset(offset, from, target.version, Bias.RIGHT) else null
            return clamp(mapped ?: offset, target.length)
        }
        return Caret(point(caret.anchor), point(caret.position))
    }

    /** [offset] clamped into a text of [length]. */
    private fun clamp(offset: Int, length: Int = text.snapshot.value.length): Int = offset.coerceIn(0, length)
}
