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
 * The carets of one editor over an [EditableText]. It is a state holder without Compose, used from the UI thread.
 *
 * Carets are stamped with the text version they refer to ([CaretSnapshot.version]). When the carets are older than the
 * text, every member first catches up synchronously by mapping them with `EditableText.mapOffset`. This lazy mapping
 * works like the one in `AutoClosedPairs`. So [current], [primary], [mementos] and every movement always act on the
 * current text. This holds even when a change, such as a reload or an edit by another component, arrived and the
 * collector in the model's scope has not run yet. The dispatcher of the scope does not matter. [state] follows the
 * changes for observers such as rendering and completion, and it may lag by one dispatch. Carets that an action sets
 * explicitly after an edit, through its `caretsAfter`, already carry the new version and are never mapped twice.
 *
 * Every explicit caret change calls `text.undo.breakCoalescing()`, so typing at another place starts a new undo step.
 * The explicit changes are [move], [moveTo], [select], [selectWord], [selectLine] and [selectAll]. [restore] and the
 * mapping through changes do not break coalescing.
 *
 * [Caret.preferredColumn] is a display column. It is computed with [TabStops] of the `tabWidth()` passed to the
 * factory. The editor passes `EditorSettings.tabDisplayWidth`, which is the same conversion the view and the status bar
 * use.
 */
public interface CaretModel {

    /** The carets and the version they refer to, for observers. It may lag behind the text by one dispatch. */
    public val state: StateFlow<CaretSnapshot>

    /** The carets mapped to the current text version. Reading it catches up synchronously when [state] lags. */
    public val current: CaretSnapshot

    /** Shortcut for `current.primary`. */
    public val primary: Caret get() = current.primary

    /**
     * Moves every caret by [movement]. If [extendSelection] is true, the anchors stay where they are, as with Shift and
     * a movement key. Otherwise selections collapse. [pageLines] is the page size for [CaretMovement.PAGE_UP] and
     * [CaretMovement.PAGE_DOWN].
     */
    public fun move(movement: CaretMovement, extendSelection: Boolean, pageLines: Int = 1)

    /**
     * Places the primary caret at [offset], clamped to the text. If [extendSelection] is true, the anchor stays where it
     * is, as with Shift+click.
     */
    public fun moveTo(offset: Int, extendSelection: Boolean = false)

    /** Replaces all carets with one caret at the given [anchor] and [position], both clamped to the text. */
    public fun select(anchor: Int, position: Int)

    /** Selects the word at [offset]. A double-click does this. */
    public fun selectWord(offset: Int)

    /** Selects [line] including its line break. A triple-click does this. */
    public fun selectLine(line: Int)

    /** Selects the whole text. */
    public fun selectAll()

    /** The carets as undo mementos. */
    public fun mementos(): List<CaretMemento>

    /**
     * Replaces the carets with [mementos], which must refer to the current text version. Undo, redo and the actions use
     * it. It does not break undo coalescing.
     */
    public fun restore(mementos: List<CaretMemento>)
}

/**
 * Creates a [CaretModel] with one caret at offset 0. It maps the carets through the changes of [text] in a collector
 * that runs in [scope]. In the editor that scope runs on the UI thread. Tests pass a scope with an
 * `UnconfinedTestDispatcher`.
 *
 * @param tabWidth columns per tab stop for [Caret.preferredColumn], see [TabStops]. It is read on every vertical move,
 *   because the setting may change at runtime.
 */
public fun CaretModel(
    text: EditableText,
    scope: CoroutineScope,
    tabWidth: () -> Int = { DEFAULT_TAB_WIDTH },
): CaretModel = DefaultCaretModel(text, scope, tabWidth)

/**
 * The default [CaretModel]. It holds the carets of one editor, stamped with a version and caught up lazily as described
 * on [CaretModel].
 *
 * [CaretMotion] computes the movements as pure geometry, and [WordBoundaries] holds the word rule. This class owns the
 * caret state, the mapping through changes and the breaks in undo coalescing on explicit moves. Carets are mapped as
 * points with `EditableText.mapOffset`. A collector in [scope] follows `text.snapshot`, so [state] catches up for
 * observers. `snapshot` is a state flow, so a collector that starts late still sees the newest version.
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

    /** Makes an explicit caret change. It ends the typing run and publishes [carets] for the current version. */
    private fun place(carets: List<Caret>) {
        text.undo.breakCoalescing()
        caretState.value = CaretSnapshot(text.snapshot.value.version, carets.distinct())
    }

    /** Maps the carets to the current version if they are older, and returns the carets, which are now current. */
    private fun catchUp(): CaretSnapshot {
        val stamped = caretState.value
        val target = text.snapshot.value
        if (stamped.version == target.version) return stamped
        val caught = CaretSnapshot(target.version, stamped.carets.map { map(it, stamped.version, target) }.distinct())
        caretState.value = caught
        return caught
    }

    /**
     * Maps [caret] from version [from] into [target]. Both ends are mapped as points with `Bias.RIGHT`, so a caret moves
     * along with text inserted at it. If the history no longer reaches [from], the offsets are only clamped. The
     * preferred column is forgotten.
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
