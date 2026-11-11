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
 * Routes what dsl-tools logs into the workbench's Language Tools Output, which the application fills through this
 * seam. The composition root attaches the output's sink for the length of a session.
 */
public fun interface LanguageToolsLogRouting {
    /** Sends every event of the dsl-tools loggers to [sink] as well, until the returned handle is closed. */
    public fun attach(sink: OutputSink): AutoCloseable
}

/**
 * The [LanguageToolsLogRouting] of logback, the IDE's logging backend. [attach] adds an [OutputSinkAppender] for a
 * sink to the parent logger of dsl-tools, [DslToolsLoggers.PARENT], in [context].
 *
 * The rest of the routing lives in the logging configuration, the `logback.xml` of this module. The dsl-tools loggers
 * log at DEBUG and do not pass their events on to the root logger, so nothing of dsl-tools reaches the console. They
 * write every event to `dsl-tools.log` next to the IDE log, and their warnings and errors also go to the IDE log
 * itself. The appender attached here is the third destination, the in-memory one the workbench shows.
 */
internal class LogbackLanguageToolsLogRouting(private val context: LoggerContext) : LanguageToolsLogRouting {

    /** Adds a started appender for [sink]. Closing the returned handle detaches the appender and stops it. */
    override fun attach(sink: OutputSink): AutoCloseable {
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

        private val log = LoggerFactory.getLogger(LogbackLanguageToolsLogRouting::class.java)

        /**
         * The routing of the application's logger context. If SLF4J does not log through logback, it logs a warning and
         * returns a routing that routes nothing, so the Language Tools Output stays empty.
         */
        fun ofApplication(): LanguageToolsLogRouting {
            val context = LoggerFactory.getILoggerFactory() as? LoggerContext
            if (context != null) return LogbackLanguageToolsLogRouting(context)
            log.warn("SLF4J does not log through logback; the Language Tools Output stays empty")
            return LanguageToolsLogRouting { AutoCloseable {} }
        }
    }
}
