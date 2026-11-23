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
package io.karpfen.ide.ui.editor.view.geometry

/**
 * A display-only shift of lines. The text area draws [added] rows below [line] that belong to no line of the text, and
 * every line after [line] is drawn [added] rows further down. The text itself does not change: offsets, lines and line
 * numbers stay those of the text, and only the rows they are drawn on move.
 *
 * Rows count the drawn lines of the text area from the top, the way lines do without a shift. The rows from [line] to
 * [line] plus [added] all show [line]. The completion preview uses the shift for the lines a proposal of several lines
 * adds. [NONE] is the identity, where every line is drawn on the row of its own index.
 *
 * @property line the line below which the rows are added.
 * @property added the number of added rows, zero or more.
 */
internal data class LineShift(val line: Int, val added: Int) {
    init {
        require(added >= 0) { "a shift adds rows, $added given" }
    }

    /** Returns the row where [line] starts. Lines after [line] move down by [added]. */
    fun rowOf(line: Int): Int = if (line > this.line) line + added else line

    /** Returns the last row that shows [line]. That is the last added row for [line] and the row of any other line. */
    fun lastRowOf(line: Int): Int = if (line == this.line) line + added else rowOf(line)

    /** Returns the line shown on [row]. The added rows show [line]. Rows outside the text are passed on unchanged. */
    fun lineAt(row: Int): Int = when {
        row <= line -> row
        row <= line + added -> line
        else -> row - added
    }

    /** Returns the number of rows that show a text of [lineCount] lines. */
    fun rowCount(lineCount: Int): Int = lineCount + added

    /** The shift that moves nothing. */
    companion object {
        /** The identity: no rows are added, and every line is drawn on its own row. */
        val NONE: LineShift = LineShift(0, 0)
    }
}
