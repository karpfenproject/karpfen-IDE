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
 * The caret part of the status bar. It holds the one-based [line] and [column] and the [selectionLength].
 *
 * The column is the display column, with tabs expanded by `TabStops`. `TabStops` is the single conversion between line
 * columns and display columns, and the caret model and the editor view use it too. `Col y` therefore matches what the
 * user sees. The selection length counts characters as UTF-16 code units.
 */
internal data class CaretPositionText(val line: Int, val column: Int, val selectionLength: Int) {

    companion object {
        /**
         * The position of [caret] in [snapshot], with tab stops every [tabWidth] columns. Offsets beyond the text are
         * clamped to it.
         */
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
