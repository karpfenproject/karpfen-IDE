package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange

/**
 * Duplicate (primary+D): with a selection, inserts a copy of it after the selection and selects the copy; without,
 * inserts a copy of the caret's line(s) below them and moves the caret into the copy (same column).
 */
internal class LineDuplication(private val name: String) : LineEdit {
    override fun execute(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        if (caret.hasSelection) {
            val selection = caret.selection
            val copy = snapshot.substring(selection)
            val start = selection.end
            val end = selection.end + copy.length
            val after = if (caret.anchor <= caret.position) CaretMemento(start, end) else CaretMemento(end, start)
            return context.applyEdits(listOf(TextEdit.insert(selection.end, copy)), EditOrigin.Command(name), listOf(after))
        }
        val block = LineBlock.of(snapshot, caret)
        val inserted = "\n" + block.text(snapshot)
        return context.applyEdit(
            TextEdit.insert(block.range(snapshot).end, inserted),
            EditOrigin.Command(name),
            caret.position + inserted.length,
        )
    }
}

/**
 * Move line up/down (Alt+Shift+Up/Down): swaps the caret's line block with the line above ([up]) or below; the carets
 * move with the block. False at the start (end) of the text.
 */
internal class LineMove(private val up: Boolean, private val name: String) : LineEdit {
    override fun execute(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        val index = snapshot.lineIndex
        val block = LineBlock.of(snapshot, caret)
        val neighbor = if (up) block.first - 1 else block.last + 1
        if (neighbor < 0 || neighbor >= snapshot.lineCount) return false
        val blockText = block.text(snapshot)
        val neighborText = snapshot.lineText(neighbor)
        val edit: TextEdit
        val shift: Int
        if (up) {
            edit = TextEdit(TextRange(index.lineStart(neighbor), index.lineEnd(block.last)), "$blockText\n$neighborText")
            shift = -(neighborText.length + 1)
        } else {
            edit = TextEdit(TextRange(index.lineStart(block.first), index.lineEnd(neighbor)), "$neighborText\n$blockText")
            shift = neighborText.length + 1
        }
        fun moved(offset: Int) = (offset + shift).coerceIn(0, snapshot.length)
        val after = CaretMemento(moved(caret.anchor), moved(caret.position))
        return context.applyEdits(listOf(edit), EditOrigin.Command(name), listOf(after))
    }
}

/**
 * Indent (Tab): without a selection spanning lines, replaces the selection by spaces up to the next 4-column stop;
 * with one, inserts one indentation level at the start of every non-empty line of the block (the selection grows over
 * the inserted indentation).
 */
internal class LineIndent(private val name: String) : LineEdit {
    override fun execute(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        val index = snapshot.lineIndex
        val selection = caret.selection
        if (index.lineOf(selection.start) == index.lineOf(selection.end)) {
            val prefix = snapshot.text.substring(index.lineStart(index.lineOf(selection.start)), selection.start)
            val spaces = Indentation.spacesToNextStop(Indentation.stops.displayColumn(prefix, prefix.length))
            return context.applyEdit(TextEdit(selection, spaces), EditOrigin.Command(name), selection.start + spaces.length)
        }
        val edits = LineBlock.of(snapshot, caret).lines
            .filter { snapshot.lineText(it).isNotEmpty() }
            .map { TextEdit.insert(index.lineStart(it), Indentation.UNIT) }
        if (edits.isEmpty()) return false
        return context.applyEdits(edits, EditOrigin.Command(name), listOf(EditShift(edits).caret(caret)))
    }
}

/**
 * Outdent (Shift+Tab): removes one indentation level from every line of the block — a leading tab, or the leading
 * spaces back to the previous 4-column stop. False if no line is indented.
 */
internal class LineOutdent(private val name: String) : LineEdit {
    override fun execute(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        val edits = LineBlock.of(snapshot, caret).lines.mapNotNull { line ->
            val text = snapshot.lineText(line)
            val start = snapshot.lineIndex.lineStart(line)
            val spaces = text.takeWhile { it == ' ' }.length
            when {
                text.startsWith('\t') -> TextEdit.delete(start, start + 1)
                spaces == 0 -> null
                spaces % Indentation.SIZE == 0 -> TextEdit.delete(start, start + Indentation.SIZE)
                else -> TextEdit.delete(start, start + spaces % Indentation.SIZE)
            }
        }
        if (edits.isEmpty()) return false
        return context.applyEdits(edits, EditOrigin.Command(name), listOf(EditShift(edits).caret(caret)))
    }
}
