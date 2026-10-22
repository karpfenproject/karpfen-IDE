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
) {
    public companion object {
        /** The contents of a buffer to which nothing was appended yet. */
        public val EMPTY: OutputSnapshot = OutputSnapshot(emptyList(), appendedErrors = 0, appendedWarnings = 0)
    }
}
