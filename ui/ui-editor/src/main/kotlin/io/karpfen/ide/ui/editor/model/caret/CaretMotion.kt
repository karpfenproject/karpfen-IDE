package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.text.TextSnapshot

/**
 * Where a [Caret] goes for a [CaretMovement] (spec §7.1): pure geometry over one [TextSnapshot], without state. The
 * [CaretModel] applies the result.
 *
 * - LEFT/RIGHT step one character (a surrogate pair is one character); without Shift a selection collapses to its
 *   start/end instead.
 * - WORD_LEFT/WORD_RIGHT use the one word rule ([WordBoundaries]).
 * - UP/DOWN and PAGE_UP/PAGE_DOWN keep the preferred column, a display column of [TabStops] with the current
 *   [tabWidth] (decision 40 of the work-package plan). A move that cannot leave the first (last) line goes to the
 *   start (end) of the document and keeps the preferred column.
 * - LINE_START is smart Home: the first non-whitespace character of the line, then column 0.
 * - Every movement but the vertical ones forgets the preferred column.
 */
internal class CaretMotion(private val tabWidth: () -> Int) {

    /** The caret after moving [caret] by [movement] in [snapshot]; [extend] keeps the anchor. */
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

    private fun smartHome(snapshot: TextSnapshot, position: Int): Int {
        val line = snapshot.lineIndex.lineOf(position)
        val start = snapshot.lineIndex.lineStart(line)
        val end = snapshot.lineIndex.lineEnd(line)
        var firstNonBlank = start
        while (firstNonBlank < end && snapshot[firstNonBlank].isWhitespace()) firstNonBlank++
        return if (position == firstNonBlank) start else firstNonBlank
    }

    private fun previousCharacter(text: String, offset: Int): Int = when {
        offset <= 0 -> 0
        offset >= 2 && text[offset - 1].isLowSurrogate() && text[offset - 2].isHighSurrogate() -> offset - 2
        else -> offset - 1
    }

    private fun nextCharacter(text: String, offset: Int): Int = when {
        offset >= text.length -> text.length
        offset + 1 < text.length && text[offset].isHighSurrogate() && text[offset + 1].isLowSurrogate() -> offset + 2
        else -> offset + 1
    }
}
