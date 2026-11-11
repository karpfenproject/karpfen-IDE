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
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextSnapshot

/** Chooses the comment marker of each line of a toggled block. It is the strategy that [LineComments] uses. */
internal fun interface CommentMarkers {
    /**
     * Returns the marker of one line of the toggled block. [offset] is the first non-blank offset of the line, or the
     * line start for a blank line. [blockStart] is the start of the first line of the block.
     */
    fun markerAt(offset: Int, blockStart: Int): String
}

/**
 * Computes and applies the edits of toggle comment over the [LineBlock] of the primary caret. [markers] gives the
 * marker of each line. [ToggleCommentAction] answers `#` for lines inside EVAL bodies that were opened above the block.
 *
 * The lines that count are the non-blank lines of the block. If every line of the block is blank, all of them count,
 * so toggling an empty line comments it. If every counted line starts with its marker after its indentation, the
 * markers are removed together with one following space. Otherwise `marker + " "` is inserted into every counted line
 * at the smallest indentation among them. All edits form one change with origin `Command(name)`.
 */
internal class LineComments(private val markers: CommentMarkers, private val name: String) : LineEdit {

    /** One counted line of the block, with its [start] offset, its [firstNonBlank] offset and its [marker]. */
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
