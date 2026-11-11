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

import io.karpfen.ide.core.text.TextSnapshot

/**
 * Computes where a [Caret] goes for a [CaretMovement]. This is pure geometry over one [TextSnapshot], without state.
 * The [CaretModel] applies the result.
 *
 * Left and right step one character, and a surrogate pair counts as one character. Without Shift, an existing
 * selection collapses to its start or end instead. Word left and word right use the editor's single word rule in
 * [WordBoundaries].
 *
 * Up, down, page up and page down keep the preferred column. That is a display column of [TabStops] with the current
 * [tabWidth], which is the same tab rule the view and the status bar use. A move up from the first line goes to the
 * start of the document, and a move down from the last line goes to its end. Both keep the preferred column.
 *
 * Line start is smart Home. It goes to the first non-whitespace character of the line, or to column 0 if the caret is
 * already there. Every movement except the vertical ones forgets the preferred column.
 */
internal class CaretMotion(private val tabWidth: () -> Int) {

    /** Returns [caret] moved by [movement] in [snapshot]. If [extend] is true, the anchor stays. */
    fun move(snapshot: TextSnapshot, caret: Caret, movement: CaretMovement, extend: Boolean, pageLines: Int): Caret {
        val text = snapshot.text
        val position = caret.position.coerceIn(0, text.length)
        val target: Int = when (movement) {
            CaretMovement.LEFT ->
                if (!extend && caret.hasSelection) caret.selection.start else previousCharacter(text, position)
            CaretMovement.RIGHT ->
                if (!extend && caret.hasSelection) caret.selection.end else nextCharacter(text, position)
            CaretMovement.WORD_LEFT -> WordBoundaries.previousWordStart(text, position)
            CaretMovement.WORD_RIGHT -> WordBoundaries.nextWordEnd(text, position)
            CaretMovement.UP -> return vertical(snapshot, caret, -1, extend)
            CaretMovement.DOWN -> return vertical(snapshot, caret, 1, extend)
            CaretMovement.PAGE_UP -> return vertical(snapshot, caret, -pageLines.coerceAtLeast(1), extend)
            CaretMovement.PAGE_DOWN -> return vertical(snapshot, caret, pageLines.coerceAtLeast(1), extend)
            CaretMovement.LINE_START -> smartHome(snapshot, position)
            CaretMovement.LINE_END -> snapshot.lineIndex.lineEnd(snapshot.lineIndex.lineOf(position))
            CaretMovement.DOCUMENT_START -> 0
            CaretMovement.DOCUMENT_END -> text.length
        }
        return Caret(anchor = if (extend) caret.anchor else target, position = target)
    }

    /**
     * Returns [caret] moved by [lines] at its preferred display column. Negative values move up. If the caret has no
     * preferred column yet, it is taken from the caret's position.
     */
    private fun vertical(snapshot: TextSnapshot, caret: Caret, lines: Int, extend: Boolean): Caret {
        val tabStops = TabStops(tabWidth())
        val position = snapshot.positionOf(caret.position.coerceIn(0, snapshot.length))
        val preferred = caret.preferredColumn ?: tabStops.displayColumn(snapshot.lineText(position.line), position.column)
        val targetLine = (position.line.toLong() + lines).coerceIn(0L, snapshot.lineCount - 1L).toInt()
        val target = when {
            targetLine != position.line ->
                snapshot.lineIndex.lineStart(targetLine) + tabStops.columnAt(snapshot.lineText(targetLine), preferred)
            lines < 0 -> 0
            else -> snapshot.length
        }
        return Caret(anchor = if (extend) caret.anchor else target, position = target, preferredColumn = preferred)
    }

    /** The first non-blank character of the line of [position], or the line start when the caret is there already. */
    private fun smartHome(snapshot: TextSnapshot, position: Int): Int {
        val line = snapshot.lineIndex.lineOf(position)
        val start = snapshot.lineIndex.lineStart(line)
        val end = snapshot.lineIndex.lineEnd(line)
        var firstNonBlank = start
        while (firstNonBlank < end && snapshot[firstNonBlank].isWhitespace()) firstNonBlank++
        return if (position == firstNonBlank) start else firstNonBlank
    }

    /** The offset one character before [offset], stepping over a surrogate pair as a whole. */
    private fun previousCharacter(text: String, offset: Int): Int = when {
        offset <= 0 -> 0
        offset >= 2 && text[offset - 1].isLowSurrogate() && text[offset - 2].isHighSurrogate() -> offset - 2
        else -> offset - 1
    }

    /** The offset one character after [offset], stepping over a surrogate pair as a whole. */
    private fun nextCharacter(text: String, offset: Int): Int = when {
        offset >= text.length -> text.length
        offset + 1 < text.length && text[offset].isHighSurrogate() && text[offset + 1].isLowSurrogate() -> offset + 2
        else -> offset + 1
    }
}
