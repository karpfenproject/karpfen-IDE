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
package io.karpfen.ide.ui.workbench.statusbar

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.model.caret.TabStops

/**
 * The caret part of the status bar: one-based [line] and [column], where the column is the display column with tabs
 * expanded by `TabStops` — the one conversion between line and display columns, which the caret model and the editor
 * view use too — so `Col y` matches what the user sees; [selectionLength] in characters (UTF-16 code units).
 */
internal data class CaretPositionText(val line: Int, val column: Int, val selectionLength: Int) {

    companion object {
        /** The position of [caret] in [snapshot] (offsets clamped to the text) with tab stops every [tabWidth] columns. */
        fun of(snapshot: TextSnapshot, caret: Caret, tabWidth: Int): CaretPositionText {
            val offset = caret.position.coerceIn(0, snapshot.length)
            val position = snapshot.positionOf(offset)
            val display = TabStops(tabWidth).displayColumn(snapshot.lineText(position.line), position.column)
            val selection = caret.selection
            val length = selection.end.coerceAtMost(snapshot.length) - selection.start.coerceAtMost(snapshot.length)
            return CaretPositionText(line = position.line + 1, column = display + 1, selectionLength = length)
        }
    }
}
