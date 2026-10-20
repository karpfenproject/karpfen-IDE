package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.text.TextRange

/**
 * Finds the brace blocks around a caret: one pass over the brace tokens before the caret with a stack of open blocks,
 * then a pass after it for their closing braces. A block contains the caret from after its `{` through before its `}`.
 */
internal object BlockLocator {

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
