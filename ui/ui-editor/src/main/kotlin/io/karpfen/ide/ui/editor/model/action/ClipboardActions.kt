package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.LineSeparators
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange

/** Kinds of [ClipboardAction]. */
internal enum class ClipboardOperation(val actionId: String) {
    COPY(EditorActionIds.COPY),
    CUT(EditorActionIds.CUT),
    PASTE(EditorActionIds.PASTE),
}

/**
 * Copy/cut (whole line without selection) and paste (separators normalized to `\n`), spec §7.1.
 *
 * Without a selection, copy and cut take the caret's line with a line break (`line + "\n"`); cut removes the line like
 * delete line ([LineBlock.deletionRange]) with origin `Cut`. Paste replaces the selection with origin `Paste` and puts
 * the caret after the pasted text. Copy and cut are enabled while there is a selection or any text; paste is always
 * enabled (enablement never reads the clipboard) and returns false for an empty clipboard.
 */
internal class ClipboardAction(private val operation: ClipboardOperation) : EditorAction {
    override val id: String = operation.actionId

    override fun isEnabled(context: EditorActionContext): Boolean = when (operation) {
        ClipboardOperation.COPY, ClipboardOperation.CUT ->
            context.caret.primary.hasSelection || context.text.snapshot.value.length > 0
        ClipboardOperation.PASTE -> true
    }

    override fun execute(context: EditorActionContext): Boolean = when (operation) {
        ClipboardOperation.COPY -> copy(context)
        ClipboardOperation.CUT -> cut(context)
        ClipboardOperation.PASTE -> paste(context)
    }

    private fun copy(context: EditorActionContext): Boolean {
        context.clipboard.setText(clipText(context))
        return true
    }

    private fun cut(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        val clip = clipText(context)
        val (range, caretAfter) = if (caret.hasSelection) {
            caret.selection to caret.selection.start
        } else {
            val block = LineBlock.of(snapshot, caret)
            block.deletionRange(snapshot) to block.caretAfterDeletion(snapshot, snapshot.positionOf(caret.position).column)
        }
        if (range.isEmpty) return false
        context.clipboard.setText(clip)
        return context.applyEdit(TextEdit.delete(range), EditOrigin.Cut, caretAfter)
    }

    private fun paste(context: EditorActionContext): Boolean {
        val pasted = context.clipboard.getText()?.let { LineSeparators.normalize(it).text }
        if (pasted.isNullOrEmpty()) return false
        val selection: TextRange = context.caret.primary.selection
        return context.applyEdit(TextEdit(selection, pasted), EditOrigin.Paste, selection.start + pasted.length)
    }

    /** The selected text, or the caret's line with a line break. */
    private fun clipText(context: EditorActionContext): String {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        return if (caret.hasSelection) {
            snapshot.substring(caret.selection)
        } else {
            snapshot.lineText(snapshot.lineIndex.lineOf(caret.position)) + "\n"
        }
    }
}
