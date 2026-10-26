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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * An in-memory ring buffer of the output of the language tools, bounded in lines and in characters: it keeps at most
 * [capacity] lines whose messages hold at most [characterBudget] characters, and drops the oldest lines for a line
 * beyond either bound; the newest line always stays. A message longer than [messageLimit] characters, such as an error
 * whose stack trace runs through a deep recursion, is cut to its first [messageLimit] characters and a note of how many
 * were cut, so a few long lines cannot push out all others, and errors repeated on every analysis cannot fill the heap.
 * The logging adapter of the application writes to it as an [OutputSink], and the workbench reads it as an
 * [OutputBuffer]; the composition root creates one for the life of the application.
 *
 * Thread-safe: appends, snapshots and clearing hold one lock for as long as it takes to change or copy the lines, and
 * the [revision] is raised under that lock, so a reader that sees a revision also sees its lines.
 *
 * @throws IllegalArgumentException if a bound is not positive or [messageLimit] exceeds [characterBudget].
 */
public class RingOutputBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val characterBudget: Int = DEFAULT_CHARACTER_BUDGET,
    private val messageLimit: Int = DEFAULT_MESSAGE_LIMIT,
) : OutputSink, OutputBuffer {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(messageLimit in 1..characterBudget) { "messageLimit must be in 1..$characterBudget, was $messageLimit" }
    }

    private val lock = Any()
    private val lines = ArrayDeque<OutputLine>()
    private var characters = 0L
    private var lastSequence = 0L
    private var errors = 0L
    private var warnings = 0L
    private val changes = MutableStateFlow(0L)

    override val revision: StateFlow<Long> get() = changes.asStateFlow()

    override fun append(time: Instant, level: OutputLevel, logger: String, message: String, file: String?) {
        val kept = cut(message)
        synchronized(lock) {
            lastSequence++
            when (level) {
                OutputLevel.ERROR -> errors++
                OutputLevel.WARNING -> warnings++
                else -> Unit
            }
            lines.addLast(OutputLine(lastSequence, time, level, logger, kept, file))
            characters += kept.length
            dropOldestBeyondBounds()
            changes.value++
        }
    }

    override fun snapshot(): OutputSnapshot = synchronized(lock) { OutputSnapshot(lines.toList(), errors, warnings) }

    override fun clear() {
        synchronized(lock) {
            lines.clear()
            characters = 0
            changes.value++
        }
    }

    /** Drops the oldest lines while the lines exceed [capacity] or their messages [characterBudget]; keeps the newest. */
    private fun dropOldestBeyondBounds() {
        while (lines.size > 1 && (lines.size > capacity || characters > characterBudget)) {
            characters -= lines.removeFirst().message.length
        }
    }

    /**
     * [message] itself if it has at most [messageLimit] characters, else its first [messageLimit] characters, one less
     * where the cut would split a surrogate pair, followed by a line that says how many characters were cut.
     */
    private fun cut(message: String): String {
        if (message.length <= messageLimit) return message
        val end = if (message[messageLimit - 1].isHighSurrogate()) messageLimit - 1 else messageLimit
        return message.substring(0, end) + OutputLine.LINE_BREAK + "… ${message.length - end} more characters"
    }

    public companion object {
        /** How many lines a buffer keeps unless told otherwise: the reader failures and warnings of many analyses. */
        public const val DEFAULT_CAPACITY: Int = 5_000

        /** How many characters the messages of a buffer hold unless told otherwise, about 4 MB of text. */
        public const val DEFAULT_CHARACTER_BUDGET: Int = 2_000_000

        /**
         * How long a message may be unless told otherwise before it is cut: room for a stack trace of a few hundred
         * frames, which tells where a failure happened, but not for one of thousands from a deep recursion.
         */
        public const val DEFAULT_MESSAGE_LIMIT: Int = 20_000
    }
}
