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
package io.karpfen.ide.tools.cli.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import org.slf4j.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [LogbackLanguageToolsLog] on a logger context of its own, configured like the tool's: the dsl-tools logger is off
 * and passes its events on to the root logger's appender; showing raises it to DEBUG until the handle is closed.
 */
class LogbackLanguageToolsLogTest {

    private val context = LoggerContext()
    private val console = ListAppender<ILoggingEvent>().apply { context = this@LogbackLanguageToolsLogTest.context; start() }
    private val dslTools = context.getLogger(DslToolsLoggers.PARENT)
    private val converter = context.getLogger("${DslToolsLoggers.PARENT}.dsl.textual.KstatesDSLConverter")

    init {
        context.getLogger(Logger.ROOT_LOGGER_NAME).apply {
            level = Level.WARN
            addAppender(console)
        }
        dslTools.level = Level.OFF
    }

    @Test
    fun `what dsl-tools logs reaches the root logger's appender only while it is shown`() {
        converter.debug("before")
        LogbackLanguageToolsLog(context).show().use {
            converter.debug("State {}", "observe")
            converter.warn("syntax error")
        }
        converter.warn("after")

        assertEquals(listOf("State observe", "syntax error"), console.list.map { it.formattedMessage })
        assertEquals(Level.OFF, dslTools.level, "closing restores the level")
    }

    @Test
    fun `closing restores a level that was not set`() {
        dslTools.level = null

        LogbackLanguageToolsLog(context).show().close()

        assertNull(dslTools.level)
    }
}
