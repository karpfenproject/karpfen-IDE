package io.karpfen.ide.ui.workbench.output

import java.time.Instant

/** The severity of an [OutputLine], from the most to the least severe; [label] is how the output view names it. */
public enum class OutputLevel(public val label: String) {
    /** An error. */
    ERROR("ERROR"),

    /** A warning. */
    WARNING("WARN"),

    /** Information. */
    INFO("INFO"),

    /** Detail for debugging, such as the models dsl-tools converts. */
    DEBUG("DEBUG"),

    /** The finest detail. */
    TRACE("TRACE"),
    ;

    /** True for the levels that report a problem, [ERROR] and [WARNING]. */
    public val isProblem: Boolean get() = this == ERROR || this == WARNING
}

/**
 * One line of the output of the language tools: one event they logged. Immutable, so it crosses from the logging
 * thread to the UI thread unchanged.
 *
 * @property sequence numbers the lines of one [OutputBuffer] in the order they arrived, from 1 on; a number is never
 *   reused, also not after the buffer was cleared, so it identifies the line.
 * @property time when the event was logged.
 * @property level the event's severity.
 * @property logger the name of the logger that logged it, such as `io.karpfen.dsl.dsl.textual.KmetaDSLConverter`.
 * @property message the formatted message; the stack trace of an exception follows it on further lines.
 */
public data class OutputLine(
    public val sequence: Long,
    public val time: Instant,
    public val level: OutputLevel,
    public val logger: String,
    public val message: String,
)
