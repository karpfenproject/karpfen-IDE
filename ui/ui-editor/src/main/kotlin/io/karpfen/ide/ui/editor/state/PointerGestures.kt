package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyModifier

/**
 * The click and drag policy of the text area, Compose-free: the view only hit-tests and reports offsets.
 *
 * A press with the [platform]'s primary modifier and one click goes to the definition at the offset (caret unchanged);
 * otherwise Shift extends the selection, one click moves the caret, two select the word, three (or more) the line.
 * Dragging extends from the press by the same unit: characters, whole words, whole lines (the unit containing the
 * press stays selected). Used from the UI thread.
 */
internal class PointerGestures(
    private val text: EditableText,
    private val caret: CaretModel,
    private val platform: HostPlatform,
    private val goToDefinition: (offset: Int) -> Unit,
) {

    /** What a drag extends the selection by: the unit of the press's click count. */
    private enum class SelectionUnit { CHARACTER, WORD, LINE }

    /** The selection unit and the range selected by the press, which a drag keeps selected. */
    private class Drag(val unit: SelectionUnit, val anchor: TextRange)

    private var drag: Drag? = null

    /** A primary-button press at [offset] with [clickCount] clicks and [modifiers] held. */
    fun pressed(offset: Int, clickCount: Int, modifiers: Set<KeyModifier>) {
        val clicks = clickCount.coerceIn(1, MAX_CLICKS)
        if (platform.primaryModifier in modifiers && clicks == 1) {
            drag = null
            goToDefinition(offset)
            return
        }
        drag = when {
            KeyModifier.SHIFT in modifiers -> {
                caret.moveTo(offset, extendSelection = true)
                Drag(SelectionUnit.CHARACTER, TextRange.empty(caret.primary.anchor))
            }
            clicks == 1 -> {
                caret.moveTo(offset)
                Drag(SelectionUnit.CHARACTER, TextRange.empty(caret.primary.position))
            }
            clicks == 2 -> {
                caret.selectWord(offset)
                Drag(SelectionUnit.WORD, caret.primary.selection)
            }
            else -> {
                caret.selectLine(text.snapshot.value.lineIndex.lineOf(clamp(offset)))
                Drag(SelectionUnit.LINE, caret.primary.selection)
            }
        }
    }

    /** The pointer is at [offset] while the button is held: extends the selection of the press. */
    fun dragged(offset: Int) {
        val current = drag ?: return
        val target = clamp(offset)
        when (current.unit) {
            SelectionUnit.CHARACTER -> caret.select(current.anchor.start, target)
            SelectionUnit.WORD -> extend(current.anchor, WordBoundaries.wordAt(text.snapshot.value.text, target))
            SelectionUnit.LINE -> extend(current.anchor, lineRangeAt(target))
        }
    }

    /** The button was released: the drag ends. */
    fun released() {
        drag = null
    }

    /** Selects from [anchor] to the far end of [target], keeping [anchor] selected. */
    private fun extend(anchor: TextRange, target: TextRange) {
        if (target.start < anchor.start) caret.select(anchor.end, target.start) else caret.select(anchor.start, target.end)
    }

    /** The line containing [offset] with its line break (what a triple click selects). */
    private fun lineRangeAt(offset: Int): TextRange {
        val snapshot = text.snapshot.value
        val line = snapshot.lineIndex.lineOf(offset)
        val end = if (line + 1 < snapshot.lineCount) snapshot.lineIndex.lineStart(line + 1) else snapshot.length
        return TextRange(snapshot.lineIndex.lineStart(line), end)
    }

    /** [offset] clamped into the current text. */
    private fun clamp(offset: Int): Int = offset.coerceIn(0, text.snapshot.value.length)

    /** The click limit. */
    private companion object {
        /** Clicks beyond a triple click count as a triple click. */
        const val MAX_CLICKS = 3
    }
}
