package io.karpfen.ide.app.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputSink

/**
 * The adapter from logback to the workbench's [OutputSink]: hands every event it receives to [sink] with its time,
 * level, logger name and message. The message is formatted with its arguments and followed by the stack trace of the
 * event's exception, if it has one, so the output shows what a log file would.
 *
 * logback calls [append] on the thread that logged, under the appender's lock; the sink is thread-safe and quick.
 */
internal class OutputSinkAppender(private val sink: OutputSink) : AppenderBase<ILoggingEvent>() {

    override fun append(event: ILoggingEvent) {
        sink.append(event.instant, outputLevelOf(event.level), event.loggerName, messageOf(event))
    }

    /** The formatted message of [event], followed by the stack trace of its exception on further lines. */
    private fun messageOf(event: ILoggingEvent): String {
        val throwable = event.throwableProxy ?: return event.formattedMessage
        return event.formattedMessage + System.lineSeparator() + ThrowableProxyUtil.asString(throwable)
    }

    companion object {
        /** The output level of the logback [level]: TRACE and anything finer count as [OutputLevel.TRACE]. */
        fun outputLevelOf(level: Level): OutputLevel = when {
            level.isGreaterOrEqual(Level.ERROR) -> OutputLevel.ERROR
            level.isGreaterOrEqual(Level.WARN) -> OutputLevel.WARNING
            level.isGreaterOrEqual(Level.INFO) -> OutputLevel.INFO
            level.isGreaterOrEqual(Level.DEBUG) -> OutputLevel.DEBUG
            else -> OutputLevel.TRACE
        }
    }
}
