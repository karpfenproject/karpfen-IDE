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
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.spi.FilterReply
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The logback adapters of the `logging` package on single events: [OutputSinkAppender] hands an event to the sink with
 * its time, level, logger and formatted message, and [DslToolsThresholdFilter] holds back the dsl-tools events below
 * its level and nothing else.
 */
class LoggingAdaptersTest {

    private val context = LoggerContext()

    private fun event(logger: String, level: Level, message: String, vararg arguments: Any?, error: Throwable? = null) =
        LoggingEvent(LoggingAdaptersTest::class.java.name, context.getLogger(logger), level, message, error, arguments)

    @Test
    fun `the appender hands each event to the sink with its time, level, logger and formatted message`() {
        val output = RingOutputBuffer()
        val appender = OutputSinkAppender(output).apply { context = this@LoggingAdaptersTest.context; start() }
        val event = event("io.karpfen.dsl.dsl.textual.KmodelDSLConverter", Level.DEBUG, "Object {} of type {}", "turtle", "Robot")
        event.setInstant(Instant.parse("2026-10-22T10:15:30.123Z"))

        appender.doAppend(event)

        assertEquals(
            listOf(
                OutputLine(
                    1, Instant.parse("2026-10-22T10:15:30.123Z"), OutputLevel.DEBUG,
                    "io.karpfen.dsl.dsl.textual.KmodelDSLConverter", "Object turtle of type Robot",
                ),
            ),
            output.snapshot().lines,
        )
    }

    @Test
    fun `an exception's stack trace follows the message`() {
        val output = RingOutputBuffer()
        val appender = OutputSinkAppender(output).apply { context = this@LoggingAdaptersTest.context; start() }

        appender.doAppend(event("io.karpfen.dsl.X", Level.ERROR, "conversion failed", error = IllegalStateException("broken")))

        val message = output.snapshot().lines.single().message
        assertTrue(message.startsWith("conversion failed" + System.lineSeparator()), message)
        assertTrue("java.lang.IllegalStateException: broken" in message, message)
        assertTrue("at io.karpfen.ide.app.logging.LoggingAdaptersTest" in message, message)
    }

    @Test
    fun `logback levels map onto the output levels`() {
        assertEquals(
            listOf(OutputLevel.ERROR, OutputLevel.WARNING, OutputLevel.INFO, OutputLevel.DEBUG, OutputLevel.TRACE, OutputLevel.TRACE),
            listOf(Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE, Level.ALL).map(OutputSinkAppender::outputLevelOf),
        )
    }

    @Test
    fun `the filter denies dsl-tools events below its level and nothing else`() {
        val filter = DslToolsThresholdFilter().apply { level = "WARN"; context = this@LoggingAdaptersTest.context; start() }

        assertEquals(FilterReply.DENY, filter.decide(event("io.karpfen.dsl.dsl.textual.KmetaDSLConverter", Level.DEBUG, "dump")))
        assertEquals(FilterReply.DENY, filter.decide(event("io.karpfen.dsl", Level.INFO, "info")))
        assertEquals(FilterReply.NEUTRAL, filter.decide(event("io.karpfen.dsl.parser.KmetaFileParser", Level.WARN, "syntax")))
        assertEquals(FilterReply.NEUTRAL, filter.decide(event("io.karpfen.dsl.X", Level.ERROR, "failed")))
        assertEquals(FilterReply.NEUTRAL, filter.decide(event("io.karpfen.ide.app.Main", Level.DEBUG, "the IDE's own")))
        assertEquals(FilterReply.NEUTRAL, filter.decide(event("io.karpfen.dslx.Other", Level.DEBUG, "not dsl-tools")))
    }

    @Test
    fun `a filter without a valid level does not start and holds nothing back`() {
        val missing = DslToolsThresholdFilter().apply { context = this@LoggingAdaptersTest.context; start() }
        val unknown = DslToolsThresholdFilter().apply { level = "LOUD"; context = this@LoggingAdaptersTest.context; start() }

        assertFalse(missing.isStarted)
        assertFalse(unknown.isStarted)
        assertEquals(FilterReply.NEUTRAL, missing.decide(event("io.karpfen.dsl.X", Level.DEBUG, "dump")))
    }
}
