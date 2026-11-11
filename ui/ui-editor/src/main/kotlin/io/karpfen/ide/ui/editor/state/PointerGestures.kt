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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyModifier

/**
 * The click and drag policy of the text area. It does not need Compose, because the view only hit-tests and reports
 * offsets.
 *
 * A single click with the primary modifier of [platform] goes to the definition at the offset and leaves the caret
 * alone. Otherwise Shift extends the selection. One click moves the caret, two clicks select the word, and three or
 * more select the line. Dragging extends the selection from the press by the same unit, which is characters, whole
 * words or whole lines. The unit that contains the press stays selected. Use it from the UI thread.
 */
internal class PointerGestures(
    private val text: EditableText,
    private val caret: CaretModel,
    private val platform: HostPlatform,
    private val goToDefinition: (offset: Int) -> Unit,
) {

    /** The unit a drag extends the selection by. The click count of the press decides it. */
    private enum class SelectionUnit { CHARACTER, WORD, LINE }

    /** The selection unit and the range selected by the press, which a drag keeps selected. */
    private class Drag(val unit: SelectionUnit, val anchor: TextRange)

    private var drag: Drag? = null

    /** Handles a press of the primary button at [offset] with [clickCount] clicks while [modifiers] are held. */
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

    /** Handles the pointer at [offset] while the button is held. It extends the selection of the press. */
    fun dragged(offset: Int) {
        val current = drag ?: return
        val target = clamp(offset)
        when (current.unit) {
            SelectionUnit.CHARACTER -> caret.select(current.anchor.start, target)
            SelectionUnit.WORD -> extend(current.anchor, WordBoundaries.wordAt(text.snapshot.value.text, target))
            SelectionUnit.LINE -> extend(current.anchor, lineRangeAt(target))
        }
    }

    /** Handles the release of the button. The drag ends. */
    fun released() {
        drag = null
    }

    /** Selects from [anchor] to the far end of [target], keeping [anchor] selected. */
    private fun extend(anchor: TextRange, target: TextRange) {
        if (target.start < anchor.start) caret.select(anchor.end, target.start) else caret.select(anchor.start, target.end)
    }

    /** Returns the line that contains [offset] with its line break. This is what a triple click selects. */
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
