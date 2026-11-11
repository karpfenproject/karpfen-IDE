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

import java.time.Instant

/** The severity of an [OutputLine], from the most to the least severe. [label] is how the output view names it. */
public enum class OutputLevel(public val label: String) {
    /** An error. */
    ERROR("ERROR"),

    /** A warning. */
    WARNING("WARN"),

    /** Information. */
    INFO("INFO"),

    /** Detail for debugging. */
    DEBUG("DEBUG"),

    /** The finest detail. */
    TRACE("TRACE"),
}

/**
 * One line of the output of the language tools, which is one event they logged. It is immutable, so it crosses from the
 * logging thread to the UI thread unchanged.
 *
 * @property sequence numbers the lines of one [OutputBuffer] in the order they arrived, starting at 1. A number is
 *   never reused, not even after the buffer was cleared, so it identifies the line.
 * @property time when the event was logged.
 * @property level the event's severity.
 * @property logger the name of the logger that logged it, such as `io.karpfen.dsl.reader.kstates.KstatesReader`.
 * @property message the formatted message. The stack trace of an exception follows it on further lines, separated by
 *   [LINE_BREAK].
 * @property file the path of the file the language tools read when they logged the event, as the IDE named it to them.
 *   Null for an event logged outside a read.
 */
public data class OutputLine(
    public val sequence: Long,
    public val time: Instant,
    public val level: OutputLevel,
    public val logger: String,
    public val message: String,
    public val file: String? = null,
) {
    public companion object {
        /** Separates the lines of a [message], on every platform. */
        public const val LINE_BREAK: String = "\n"
    }
}
