package io.karpfen.ide.app.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import io.karpfen.ide.core.document.dsl.DslToolsLoggers

/**
 * A logback filter that holds back what dsl-tools logs below a level: it denies an event of a dsl-tools logger
 * ([DslToolsLoggers.isDslToolsLogger]) whose level is lower than [level], and stays neutral on every other event.
 * `logback.xml` puts it on the IDE log, so that of all that dsl-tools logs only the warnings and errors are forwarded
 * there, while the IDE's own events pass unchanged.
 *
 * logback creates the filter from the configuration and sets [level] through its setter before [start].
 *
 * @property level the name of the lowest level of the dsl-tools events that pass, such as `WARN`.
 */
public class DslToolsThresholdFilter : Filter<ILoggingEvent>() {
    public var level: String? = null
    private var threshold: Level = Level.OFF

    /** Starts the filter if [level] names a level; reports anything else as an error of the configuration. */
    override fun start() {
        val parsed = level?.let { Level.toLevel(it, null) }
        if (parsed == null) {
            addError("The filter needs a level such as WARN, was '$level'")
            return
        }
        threshold = parsed
        super.start()
    }

    override fun decide(event: ILoggingEvent): FilterReply {
        val heldBack = isStarted && DslToolsLoggers.isDslToolsLogger(event.loggerName) && !event.level.isGreaterOrEqual(threshold)
        return if (heldBack) FilterReply.DENY else FilterReply.NEUTRAL
    }
}
