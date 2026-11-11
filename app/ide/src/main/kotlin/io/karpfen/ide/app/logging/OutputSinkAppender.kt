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
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.output.OutputSink

/**
 * The adapter from logback to the workbench's [OutputSink]. It hands every event it receives to [sink] with its time,
 * level, logger name and message. It also passes the file that the event's mapped diagnostic context names under
 * [DslToolsLoggers.FILE_KEY], which is the file dsl-tools was reading when it logged.
 *
 * The message is formatted with its arguments. If the event has an exception, the message is followed by its stack
 * trace, so the output shows what a log file would. The lines are separated by [OutputLine.LINE_BREAK], whatever line
 * separator the platform uses, because logback writes the platform's separator into stack traces. The message ends
 * without a line break, although a stack trace from logback otherwise ends with one, so the output view shows no empty
 * last line.
 *
 * logback calls [append] on the thread that logged, under the appender's lock. The sink is thread-safe and quick.
 */
internal class OutputSinkAppender(private val sink: OutputSink) : AppenderBase<ILoggingEvent>() {

    /** Hands [event] to the sink with its time, output level, logger name, message and file. */
    override fun append(event: ILoggingEvent) {
        sink.append(event.instant, outputLevelOf(event.level), event.loggerName, messageOf(event), event.mdcPropertyMap[DslToolsLoggers.FILE_KEY])
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
        /** The output level of the logback [level]. TRACE and anything finer count as [OutputLevel.TRACE]. */
        fun outputLevelOf(level: Level): OutputLevel = when {
            level.isGreaterOrEqual(Level.ERROR) -> OutputLevel.ERROR
            level.isGreaterOrEqual(Level.WARN) -> OutputLevel.WARNING
            level.isGreaterOrEqual(Level.INFO) -> OutputLevel.INFO
            level.isGreaterOrEqual(Level.DEBUG) -> OutputLevel.DEBUG
            else -> OutputLevel.TRACE
        }
    }
}
