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
package io.karpfen.ide.app.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.output.OutputSink

/**
 * The adapter from logback to the workbench's [OutputSink]: hands every event it receives to [sink] with its time,
 * level, logger name and message. The message is formatted with its arguments and followed by the stack trace of the
 * event's exception, if it has one, so the output shows what a log file would. Its lines are separated by
 * [OutputLine.LINE_BREAK] whatever the platform's line separator, which logback writes into stack traces, and it ends
 * without a line break, which a stack trace of logback otherwise ends with, so the output view shows no empty last line.
 *
 * logback calls [append] on the thread that logged, under the appender's lock; the sink is thread-safe and quick.
 */
internal class OutputSinkAppender(private val sink: OutputSink) : AppenderBase<ILoggingEvent>() {

    override fun append(event: ILoggingEvent) {
        sink.append(event.instant, outputLevelOf(event.level), event.loggerName, messageOf(event))
    }

    /** The formatted message of [event], followed by the stack trace of its exception on further lines. */
    private fun messageOf(event: ILoggingEvent): String {
        val message = withLineBreaks(event.formattedMessage)
        val throwable = event.throwableProxy ?: return message
        return message + OutputLine.LINE_BREAK + withLineBreaks(ThrowableProxyUtil.asString(throwable))
    }

    /** The lines of [text] separated by [OutputLine.LINE_BREAK], without a line break at its end. */
    private fun withLineBreaks(text: String): String =
        text.lineSequence().joinToString(OutputLine.LINE_BREAK).trimEnd(*OutputLine.LINE_BREAK.toCharArray())

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
