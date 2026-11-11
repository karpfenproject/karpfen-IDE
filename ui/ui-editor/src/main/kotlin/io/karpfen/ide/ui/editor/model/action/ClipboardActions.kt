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
import io.karpfen.ide.core.text.LineSeparators
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange

/**
 * Kinds of [ClipboardAction].
 *
 * @property actionId the id of the action of this kind.
 */
internal enum class ClipboardOperation(val actionId: String) {
    COPY(EditorActionIds.COPY),
    CUT(EditorActionIds.CUT),
    PASTE(EditorActionIds.PASTE),
}

/**
 * Copy, cut and paste.
 *
 * Without a selection, copy and cut take the caret's whole line plus a line break (`line + "\n"`) and mark it as a
 * whole line on the clipboard. Cut then removes the line the same way delete line does ([LineBlock.deletionRange]),
 * with origin `Cut`. Cut deletes only after the clipboard took the text, so a clipboard that cannot be written loses
 * nothing.
 *
 * Paste normalizes line separators to `\n`. It replaces the selection with origin `Paste` and puts the caret after the
 * pasted text. A whole line on the clipboard is handled differently when there is no selection. It is inserted at the
 * start of the caret's line, and the caret keeps its place in its line. This follows the IntelliJ convention, where
 * copy and paste duplicates a line and cut and paste moves it.
 *
 * Copy and cut are enabled while there is a selection or any text at all. Paste is always enabled, because enablement
 * never reads the clipboard. It returns false for an empty clipboard.
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

    private fun copy(context: EditorActionContext): Boolean =
        context.clipboard.setText(clipText(context), wholeLine = !context.caret.primary.hasSelection)

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
        if (range.isEmpty || !context.clipboard.setText(clip, wholeLine = !caret.hasSelection)) return false
        return context.applyEdit(TextEdit.delete(range), EditOrigin.Cut, caretAfter)
    }

    private fun paste(context: EditorActionContext): Boolean {
        val pasted = context.clipboard.getText()?.let { LineSeparators.normalize(it).text }
        if (pasted.isNullOrEmpty()) return false
        val caret = context.caret.primary
        if (!caret.hasSelection && context.clipboard.holdsWholeLine()) {
            val snapshot = context.text.snapshot.value
            val lineStart = snapshot.lineIndex.lineStart(snapshot.lineIndex.lineOf(caret.position))
            return context.applyEdit(TextEdit.insert(lineStart, pasted), EditOrigin.Paste, caret.position + pasted.length)
        }
        val selection: TextRange = caret.selection
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
