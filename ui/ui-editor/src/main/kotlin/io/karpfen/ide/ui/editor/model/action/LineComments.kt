package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextSnapshot

/** Chooses the comment marker of each line of a toggled block (Strategy of [LineComments]). */
internal fun interface CommentMarkers {
    /**
     * The marker of the line whose first non-blank offset (its start when blank) is [offset], when the block starting
     * at offset [blockStart] (the start of its first line) is toggled.
     */
    fun markerAt(offset: Int, blockStart: Int): String
}

/**
 * The edits of toggle comment over the caret's [LineBlock], with the marker of each line given by [markers]
 * ([ToggleCommentAction] answers `#` inside EVAL bodies opened above the block).
 *
 * The lines considered are the block's non-blank lines, or all of its lines when every one is blank (so toggling an
 * empty line comments it). If every considered line starts with its marker after its indentation, the markers (and
 * one following space) are removed; otherwise `marker + " "` is inserted into every considered line at the smallest
 * indentation of them. One change, origin `Command(name)`.
 */
internal class LineComments(private val markers: CommentMarkers, private val name: String) : LineEdit {

    /** One line of the block: [start] of the line, [firstNonBlank] offset, its [marker]. */
    private class CommentLine(val start: Int, val firstNonBlank: Int, val marker: String)

    override fun execute(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        val block = LineBlock.of(snapshot, caret)
        val blank = block.lines.filter { snapshot.lineText(it).isBlank() }.toSet()
        val considered = block.lines.filter { it !in blank }.ifEmpty { block.lines.toList() }
        val blockStart = snapshot.lineIndex.lineStart(block.first)
        val lines = considered.map { line ->
            val start = snapshot.lineIndex.lineStart(line)
            val firstNonBlank = Indentation.firstNonBlank(snapshot, line)
            CommentLine(start, firstNonBlank, markers.markerAt(if (line in blank) start else firstNonBlank, blockStart))
        }
        val commented = lines.all { snapshot.text.startsWith(it.marker, it.firstNonBlank) }
        val edits = if (commented) uncomment(snapshot, lines) else comment(lines)
        return context.applyEdits(edits, EditOrigin.Command(name), listOf(EditShift(edits).caret(caret)))
    }

    /** Inserts each line's marker and a space at the smallest indentation of [lines]. */
    private fun comment(lines: List<CommentLine>): List<TextEdit> {
        val indent = lines.minOf { it.firstNonBlank - it.start }
        return lines.map { TextEdit.insert(it.start + indent, it.marker + " ") }
    }

    /** Removes each line's marker and the one space following it, if any. */
    private fun uncomment(snapshot: TextSnapshot, lines: List<CommentLine>): List<TextEdit> = lines.map {
        val markerEnd = it.firstNonBlank + it.marker.length
        val end = if (markerEnd < snapshot.length && snapshot[markerEnd] == ' ') markerEnd + 1 else markerEnd
        TextEdit.delete(it.firstNonBlank, end)
    }
}
