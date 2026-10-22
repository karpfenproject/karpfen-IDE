package io.karpfen.ide.core.document.dsl

/**
 * The loggers of karpfen-dsl-tools. dsl-tools logs through SLF4J and names its loggers after its classes, so every one of
 * them lies below one parent logger, [PARENT]: the converters log what they convert at DEBUG, and the file parsers log
 * syntax errors as warnings.
 *
 * The applications route this logging by the parent's name: the IDE keeps it off its console and shows it in its
 * Language Tools Output, and the command-line tool keeps it off unless asked for with `--verbose`. Their logging
 * configurations name the same logger, and their tests check that both agree with [PARENT].
 */
public object DslToolsLoggers {
    /** The name of the parent logger of every logger of dsl-tools. */
    public const val PARENT: String = "io.karpfen.dsl"

    /** True if [loggerName] names [PARENT] or a logger below it. */
    public fun isDslToolsLogger(loggerName: String): Boolean = loggerName == PARENT || loggerName.startsWith("$PARENT.")
}
