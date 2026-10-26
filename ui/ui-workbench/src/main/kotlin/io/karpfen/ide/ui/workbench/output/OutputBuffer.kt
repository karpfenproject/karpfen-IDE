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

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Where the events the language tools log arrive: the port that the application's logging adapter writes to. Called
 * from any thread, such as the analysis thread, so implementations are thread-safe and do not block for long.
 */
public fun interface OutputSink {
    /** Appends the event logged at [time] by [logger] with [level] and the formatted [message]. */
    public fun append(time: Instant, level: OutputLevel, logger: String, message: String)
}

/**
 * The buffered output of the language tools as the workbench reads it: the port behind the Language Tools Output tool
 * window. Thread-safe.
 *
 * A reader follows [revision] and reads a [snapshot] for each new value. The revision only says that something changed,
 * so appending stays cheap however large the buffer is, and a slow reader skips intermediate revisions instead of
 * copying the lines for each of them.
 */
public interface OutputBuffer {
    /** A number that changes with every append and every [clear]. */
    public val revision: StateFlow<Long>

    /** The current contents, an immutable copy. */
    public fun snapshot(): OutputSnapshot

    /** Removes every buffered line; the counts of [OutputSnapshot] keep counting the lines appended before. */
    public fun clear()
}

/**
 * The contents of an [OutputBuffer] at one moment.
 *
 * @property lines the buffered lines, oldest first, at most as many as the buffer holds.
 * @property appendedErrors how many errors were appended since the buffer was created, including those cleared or
 *   pushed out of the buffer since.
 * @property appendedWarnings how many warnings were appended since the buffer was created, counted like
 *   [appendedErrors].
 */
public data class OutputSnapshot(
    public val lines: List<OutputLine>,
    public val appendedErrors: Long,
    public val appendedWarnings: Long,
)
