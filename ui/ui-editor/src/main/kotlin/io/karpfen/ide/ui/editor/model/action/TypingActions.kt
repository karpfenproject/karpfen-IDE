package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.LineSeparators
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Types [text]: replaces selections; auto-closes `"`, `(`, `{` when the next character is whitespace,
 * end of line or a closing bracket, and over-types an auto-inserted closer. Origin `Typing`, one undo step.
 *
 * Details: separators in [text] are normalized to `\n`, and a typed `\n` is Enter ([EnterAction], auto-indent).
 * Over-typing moves the caret over a closer that [AutoClosedPairs] tracks (closers the user typed are never tracked,
 * so typing `)` before one inserts a new `)`). A `"` auto-closes only outside a string of its line ([StringLiterals]),
 * so typing the closing quote of a string never inserts a pair. Auto-close and over-type apply to a single typed
 * character without selection; anything else is inserted as is.
 */
internal class TypeTextAction(private val text: String) : EditorAction {
    override val id: String = EditorActionIds.TYPE_TEXT

    override fun execute(context: EditorActionContext): Boolean {
        val typed = LineSeparators.normalize(text).text
        if (typed.isEmpty()) return false
        if (typed == "\n") return lineBreak.execute(context)
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        if (typed.length == 1 && !caret.hasSelection) {
            val char = typed[0]
            val position = caret.position
            if (overTypes(context, snapshot, position, char)) {
                context.autoPairs.forget(position)
                context.caret.restore(listOf(CaretMemento.at(position + 1)))
                return true
            }
            val closer = CLOSER_OF[char]
            if (closer != null && autoCloses(snapshot, position, char)) {
                val inserted = context.applyEdit(TextEdit.insert(position, "$char$closer"), EditOrigin.Typing, position + 1)
                if (inserted) context.autoPairs.record(position, position + 1)
                return inserted
            }
        }
        val selection = caret.selection
        return context.applyEdit(TextEdit(selection, typed), EditOrigin.Typing, selection.start + typed.length)
    }

    /** Whether typing [char] at [position] moves over a tracked auto-inserted closer instead of inserting it. */
    private fun overTypes(context: EditorActionContext, snapshot: TextSnapshot, position: Int, char: Char): Boolean =
        char in CLOSER_OF.values && position < snapshot.length && snapshot[position] == char &&
            context.autoPairs.isAutoClosedCloser(position)

    /**
     * Whether typing the opener [char] at [position] inserts its closer as well: the next character is whitespace, the
     * line end or a closing bracket, and a `"` does not close a string of its line.
     */
    private fun autoCloses(snapshot: TextSnapshot, position: Int, char: Char): Boolean {
        val next = if (position < snapshot.length) snapshot[position] else '\n'
        val nextAllows = next.isWhitespace() || next in CLOSING_BRACKETS
        return nextAllows && (char != '"' || !StringLiterals.isInsideString(snapshot, position))
    }

    /** The auto-closed pairs and the shared Enter action. */
    private companion object {
        /** The closer auto-inserted after each opener. */
        val CLOSER_OF: Map<Char, Char> = mapOf('"' to '"', '(' to ')', '{' to '}')

        /** The characters before which an opener auto-closes, besides whitespace and the line end. */
        const val CLOSING_BRACKETS: String = ")]}"

        /** What a typed line break runs. */
        val lineBreak = EnterAction()
    }
}

/**
 * Enter: new line with the indentation of the current line, +4 spaces after a line ending in `{`; between
 * `{` and `}` the `}` moves to its own line.
 *
 * Details: replaces a selection; the indentation is the leading whitespace of the caret's line up to the caret (the
 * text before the caret, trimmed, decides "ends in `{`"); between `{` and `}` (whitespace around the caret allowed) the
 * whitespace before `}` is dropped and the caret lands on the indented middle line. Origin `Typing`, one undo step.
 */
internal class EnterAction : EditorAction {
    override val id: String = EditorActionIds.ENTER

    override fun execute(context: EditorActionContext): Boolean {
        val snapshot = context.text.snapshot.value
        val selection = context.caret.primary.selection
        val index = snapshot.lineIndex
        val before = snapshot.text.substring(index.lineStart(index.lineOf(selection.start)), selection.start)
        val after = snapshot.text.substring(selection.end, index.lineEnd(index.lineOf(selection.end)))
        val indent = Indentation.leadingWhitespace(before)
        val opensBlock = before.trimEnd().endsWith('{')
        val inner = if (opensBlock) indent + Indentation.UNIT else indent
        val closerOffset = Indentation.leadingWhitespace(after).length
        val closesBlock = opensBlock && after.getOrNull(closerOffset) == '}'
        val caretAfter = selection.start + 1 + inner.length
        return if (closesBlock) {
            val replaced = TextRange(selection.start, selection.end + closerOffset)
            context.applyEdit(TextEdit(replaced, "\n$inner\n$indent"), EditOrigin.Typing, caretAfter)
        } else {
            context.applyEdit(TextEdit(selection, "\n$inner"), EditOrigin.Typing, caretAfter)
        }
    }
}

/**
 * Kinds of [DeletionAction].
 *
 * @property actionId the id of the action of this kind.
 */
internal enum class DeletionKind(val actionId: String) {
    BACKSPACE(EditorActionIds.BACKSPACE),
    DELETE(EditorActionIds.DELETE),
    WORD_LEFT(EditorActionIds.DELETE_WORD_LEFT),
    WORD_RIGHT(EditorActionIds.DELETE_WORD_RIGHT),
    LINE(EditorActionIds.DELETE_LINE),
}

/**
 * Deletion: selection first; Backspace smart-unindents to the previous 4-column stop inside leading
 * whitespace and deletes an auto-inserted pair; word and line deletion.
 *
 * Details: Backspace and Delete remove one character (a surrogate pair as a whole) with origin `Typing`, so runs of
 * them coalesce into one undo step; Backspace between a tracked pair (`(|)`, [AutoClosedPairs]) removes both. Word
 * deletion removes up to the previous word start / next word end of the one word rule ([DeletionRules]); line deletion
 * removes the lines of the selection ([LineBlock]) and keeps the caret's column. Both are commands (one step each).
 */
internal class DeletionAction(private val kind: DeletionKind) : EditorAction {
    override val id: String = kind.actionId

    override fun execute(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        if (kind == DeletionKind.LINE) return deleteLines(context, snapshot)
        if (caret.hasSelection) return delete(context, caret.selection, EditOrigin.Typing)
        val position = caret.position
        val range = when (kind) {
            DeletionKind.BACKSPACE -> DeletionRules.backspace(snapshot, position, context.autoPairs)
            DeletionKind.DELETE -> DeletionRules.forward(snapshot, position)
            DeletionKind.WORD_LEFT -> DeletionRules.wordLeft(snapshot, position)
            DeletionKind.WORD_RIGHT -> DeletionRules.wordRight(snapshot, position)
            DeletionKind.LINE -> error("handled above")
        } ?: return false
        val origin = if (kind == DeletionKind.BACKSPACE || kind == DeletionKind.DELETE) {
            EditOrigin.Typing
        } else {
            EditOrigin.Command(EditorActionTitles.of(id))
        }
        return delete(context, range, origin)
    }

    /** Deletes [range] with [origin], leaving the caret at its start. */
    private fun delete(context: EditorActionContext, range: TextRange, origin: EditOrigin): Boolean =
        context.applyEdit(TextEdit.delete(range), origin, range.start)

    /** Deletes the caret's [LineBlock] as a command, keeping the caret's column on the line that takes its place. */
    private fun deleteLines(context: EditorActionContext, snapshot: TextSnapshot): Boolean {
        val caret = context.caret.primary
        val block = LineBlock.of(snapshot, caret)
        val column = snapshot.positionOf(caret.position).column
        val caretAfter = block.caretAfterDeletion(snapshot, column)
        return context.applyEdit(TextEdit.delete(block.deletionRange(snapshot)), EditOrigin.Command(EditorActionTitles.of(id)), caretAfter)
    }
}
