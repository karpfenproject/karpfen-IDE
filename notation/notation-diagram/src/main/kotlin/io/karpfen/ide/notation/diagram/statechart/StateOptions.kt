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
package io.karpfen.ide.notation.diagram.statechart

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.text.TextBlocks

/** What a state remembers about the states nested in it while it is not active. */
public enum class StateHistory {
    /** The state remembers nothing. */
    NONE,

    /** `SHALLOW HISTORY`: the state remembers the directly nested state that was active last. */
    SHALLOW,

    /** `DEEP HISTORY`: the state remembers the innermost state that was active last. */
    DEEP,
}

/**
 * The timeout of a state, `TIMEOUT: AFTER 10 TICKS TRANSITION TO "state"`.
 *
 * @property ticks the number of ticks after which the state is left.
 * @property target the name of the state the timeout leads to.
 */
public data class StateTimeout(public val ticks: Long, public val target: String)

/**
 * The two blocks that may open the body of a state, in the order the language wants them.
 *
 * @property breakpoint the whole `BREAKPOINT { ... }` block, or null if the state has none.
 * @property tickLimit the whole `TICK LIMIT { ... }` block, or null if the state has none.
 */
internal data class LeadingBlocks(val breakpoint: TextRange?, val tickLimit: TextRange?)

/**
 * Reads from the text of a state or a transition what the outline of the document leaves out: the history in front of
 * `STATE`, the `BREAKPOINT` block and the `TICK LIMIT` block with its delay and its timeout.
 *
 * The language puts these blocks first in a body, the breakpoints before the tick limit. So the reader only looks at
 * what opens a body and never mistakes a block of a nested state for one of the state around it. Blanks and line
 * comments between the blocks are passed over.
 */
internal object StateOptionReader {

    private val SHALLOW = Regex("""\bSHALLOW\s+HISTORY\b""")
    private val DEEP = Regex("""\bDEEP\s+HISTORY\b""")
    private val BREAKPOINT = Regex("""BREAKPOINT\s*\{""")
    private val TICK_LIMIT = Regex("""TICKS?\s+LIMIT\s*\{""")
    private val DELAY = Regex("""\bDELAY\s*:\s*(\d+)\s+TICKS?\b""")
    private val TIMEOUT = Regex("""\bTIMEOUT\s*:\s*AFTER\s+(\d+)\s+TICKS?\s+TRANSITION\s+TO\s+"([^"]*)"""")
    private val WORD = Regex("""\b[A-Z]+\b""")

    /** The history that [header] names, the text of a state from its first word up to its name. */
    fun historyOf(header: String): StateHistory = when {
        SHALLOW.containsMatchIn(header) -> StateHistory.SHALLOW
        DEEP.containsMatchIn(header) -> StateHistory.DEEP
        else -> StateHistory.NONE
    }

    /** The blocks that open the body of a state of [text] whose opening brace is at [open]. */
    fun leadingOf(text: String, open: Int): LeadingBlocks {
        val breakpoint = blockAt(text, open + 1, BREAKPOINT)
        val tickLimit = blockAt(text, breakpoint?.end ?: (open + 1), TICK_LIMIT)
        return LeadingBlocks(breakpoint, tickLimit)
    }

    /** The `BREAKPOINT` block that opens the body of a transition of [text] whose opening brace is at [open], or null. */
    fun breakpointOf(text: String, open: Int): TextRange? = blockAt(text, open + 1, BREAKPOINT)

    /** The words between the braces of the breakpoint block [block] of [text], such as `ENTRY` and `EXIT`. */
    fun wordsOf(text: String, block: TextRange?): Set<String> =
        if (block == null) emptySet() else WORD.findAll(TextBlocks.inner(text, block)).map { it.value }.toSet()

    /** The ticks of the `DELAY` in the tick limit block [block] of [text], or null if it has none. */
    fun delayOf(text: String, block: TextRange?): Long? =
        block?.let { DELAY.find(text.substring(it.start, it.end)) }?.groupValues?.get(1)?.toLongOrNull()

    /** The `TIMEOUT` in the tick limit block [block] of [text], or null if it has none. */
    fun timeoutOf(text: String, block: TextRange?): StateTimeout? {
        val match = block?.let { TIMEOUT.find(text.substring(it.start, it.end)) } ?: return null
        return match.groupValues[1].toLongOrNull()?.let { StateTimeout(it, match.groupValues[2]) }
    }

    /**
     * The block that [opening] opens at the first word at or after [from] of [text], or null if another word comes
     * first.
     */
    private fun blockAt(text: String, from: Int, opening: Regex): TextRange? {
        val start = afterBlanks(text, from)
        val match = opening.matchAt(text, start) ?: return null
        return TextRange(start, TextBlocks.closing(text, match.range.last) + 1)
    }

    /** The index of the first character at or after [from] of [text] that is neither a blank nor in a line comment. */
    private fun afterBlanks(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            index = when {
                text[index].isWhitespace() -> index + 1
                text.startsWith(KarpfenSyntax.LINE_COMMENT, index) -> text.indexOf('\n', index).let { if (it < 0) text.length else it + 1 }
                else -> return index
            }
        }
        return index
    }
}
