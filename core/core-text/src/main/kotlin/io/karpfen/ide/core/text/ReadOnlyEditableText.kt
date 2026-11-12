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
package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This text as one that a user cannot change. It shows a file that the IDE keeps up to date but that is not meant to
 * be edited by hand, such as the companion file of a diagram.
 *
 * Only a change with the origin [EditOrigin.External] goes through, which is how the content follows the file on
 * disk. Every other change is refused and returns null, as a change that does nothing would. So typing, pasting,
 * commands and replacing all end at the same place, and no caller needs to know that the text is read-only. Undo and
 * redo have nothing to offer, because there is no history a user made.
 */
public fun EditableText.readOnly(): EditableText = ReadOnlyEditableText(this)

/** The [EditableText] behind [readOnly]. It hands everything to [delegate] except the changes a user could make. */
private class ReadOnlyEditableText(private val delegate: EditableText) : EditableText by delegate {

    override val undo: UndoManager = NoUserHistory(delegate.undo)

    override fun apply(
        edits: List<TextEdit>,
        origin: EditOrigin,
        caretsBefore: List<CaretMemento>?,
        caretsAfter: List<CaretMemento>?,
    ): TextChange? = if (origin == EditOrigin.External) delegate.apply(edits, origin, caretsBefore, caretsAfter) else null

    override fun replaceAll(newText: String, origin: EditOrigin): TextChange? =
        if (origin == EditOrigin.External) delegate.replaceAll(newText, origin) else null

    /**
     * The undo history of the text without anything to undo or redo. The save point is still the one of [history], so
     * a reload from disk is marked as saved the usual way.
     */
    private class NoUserHistory(history: UndoManager) : UndoManager by history {
        private val never = MutableStateFlow(false).asStateFlow()

        override val canUndo: StateFlow<Boolean> = never
        override val canRedo: StateFlow<Boolean> = never

        override fun undo(): UndoResult? = null

        override fun redo(): UndoResult? = null
    }
}
