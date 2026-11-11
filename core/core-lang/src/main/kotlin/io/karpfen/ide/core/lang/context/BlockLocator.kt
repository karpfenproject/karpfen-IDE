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
package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.text.TextRange

/**
 * Finds the brace blocks around a caret. One pass goes over the brace tokens before the caret with a stack of open
 * blocks. A second pass after the caret finds their closing braces. A block contains the caret from after its `{` up to
 * before its `}`.
 */
internal object BlockLocator {

    /** The blocks of [view] that contain [caret], outermost first. An unclosed block runs to the end of the text. */
    fun locate(view: TokenView, caret: Int): List<BlockFrame> {
        val tokens = view.tokens
        val open = ArrayList<Int>()
        var i = 0
        while (i < tokens.size && tokens[i].range.end <= caret) {
            when (view.punctuationOf(tokens[i])) {
                '{' -> open += i
                '}' -> open.removeLastOrNull()
            }
            i++
        }
        val closes = arrayOfNulls<TextRange>(open.size)
        var unclosed = open.size
        var depth = 0
        while (i < tokens.size && unclosed > 0) {
            when (view.punctuationOf(tokens[i])) {
                '{' -> depth++
                '}' -> if (depth > 0) depth-- else closes[--unclosed] = tokens[i].range
            }
            i++
        }
        return open.mapIndexed { level, index ->
            val start = tokens[index].range.start
            BlockFrame(view.statementBefore(index), TextRange(start, maxOf(start, closes[level]?.end ?: view.text.length)))
        }
    }
}
