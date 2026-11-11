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
package io.karpfen.ide.ui.workbench.output

/**
 * Where the user left the Language Tools Output. It names the line at the top of the view by its [sequence], and it
 * says by [offset] how many pixels of that line are scrolled out of sight above the view. The output state keeps it
 * while the tool window is hidden, so the window opens where the user left it.
 *
 * @property sequence the [OutputLine.sequence] of the line at the top of the view.
 * @property offset how far that line is scrolled up out of sight, in pixels.
 */
public data class OutputReadingPosition(public val sequence: Long, public val offset: Int) {

    /**
     * The index of the line among [lines] to show at the top again. That is the line of [sequence]. If that line was
     * dropped from the buffer since, it is the oldest line that came after it. The index is 0 if every line came
     * after it, as happens after clearing.
     */
    public fun indexIn(lines: List<OutputLine>): Int = lines.indexOfFirst { it.sequence >= sequence }.coerceAtLeast(0)

    /**
     * How far to scroll the line at [indexIn] out of sight. That is [offset] for the line of [sequence] and 0 for any
     * other.
     */
    public fun offsetIn(lines: List<OutputLine>): Int = if (lines.getOrNull(indexIn(lines))?.sequence == sequence) offset else 0
}
