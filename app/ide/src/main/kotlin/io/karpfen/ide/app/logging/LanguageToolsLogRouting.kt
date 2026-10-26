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

import ch.qos.logback.classic.LoggerContext
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import io.karpfen.ide.ui.workbench.output.OutputSink
import org.slf4j.LoggerFactory

/**
 * Routes what dsl-tools logs into the workbench's Language Tools Output: [attach] adds an [OutputSinkAppender] for a
 * sink to the parent logger of dsl-tools ([DslToolsLoggers.PARENT]) in [context].
 *
 * The rest of the routing is the logging configuration's, `logback.xml` of this module: the dsl-tools loggers log at
 * DEBUG and do not pass their events on to the root logger, so nothing of dsl-tools reaches the console; they write
 * every event to `dsl-tools.log` next to the IDE log, and their warnings and errors also to the IDE log itself. The
 * appender attached here is the third destination, the in-memory one the workbench shows.
 */
internal class LanguageToolsLogRouting(private val context: LoggerContext) {

    /** Sends every event of the dsl-tools loggers to [sink] as well, until the returned handle is closed. */
    fun attach(sink: OutputSink): AutoCloseable {
        val appender = OutputSinkAppender(sink)
        appender.context = context
        appender.name = APPENDER_NAME
        appender.start()
        val logger = context.getLogger(DslToolsLoggers.PARENT)
        logger.addAppender(appender)
        return AutoCloseable {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    companion object {
        /** The name of the appender that [attach] adds. */
        const val APPENDER_NAME: String = "LANGUAGE_TOOLS_OUTPUT"

        private val log = LoggerFactory.getLogger(LanguageToolsLogRouting::class.java)

        /**
         * The routing of the application's logger context, or null if SLF4J does not log through logback (then nothing
         * reaches the Language Tools Output, which a warning says).
         */
        fun ofApplication(): LanguageToolsLogRouting? {
            val context = LoggerFactory.getILoggerFactory() as? LoggerContext
            if (context == null) log.warn("SLF4J does not log through logback; the Language Tools Output stays empty")
            return context?.let(::LanguageToolsLogRouting)
        }
    }
}
