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
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import org.slf4j.LoggerFactory

/**
 * Shows what dsl-tools logs in the command-line tool. The log is off by default, so the tool's console carries only
 * the report and the tool's own messages. Every problem dsl-tools finds still reaches the report as a diagnostic. A
 * command run with `--verbose` shows the log on standard error for the length of the run.
 */
internal fun interface LanguageToolsLog {
    /**
     * Shows what dsl-tools logs at DEBUG and above, the finest level it logs at, on standard error until the returned
     * handle is closed.
     */
    fun show(): AutoCloseable
}

/**
 * The [LanguageToolsLog] of logback, the tool's logging backend. `logback.xml` turns the parent logger of dsl-tools,
 * [DslToolsLoggers.PARENT], off and lets it pass its events on to the root logger, whose only appender writes to
 * standard error. [show] raises that logger to DEBUG, the finest level dsl-tools logs at, so everything dsl-tools logs
 * takes that way. Closing the handle restores the level the logger had.
 */
internal class LogbackLanguageToolsLog(private val context: LoggerContext) : LanguageToolsLog {

    /** Raises the parent logger of dsl-tools to DEBUG until the returned handle is closed. */
    override fun show(): AutoCloseable {
        val logger = context.getLogger(DslToolsLoggers.PARENT)
        val previous = logger.level
        logger.level = Level.DEBUG
        return AutoCloseable { logger.level = previous }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LogbackLanguageToolsLog::class.java)

        /**
         * The [LanguageToolsLog] of the tool's logger context. If SLF4J does not log through logback, it returns one
         * that shows nothing and logs a warning each time it is asked to show.
         */
        fun ofApplication(): LanguageToolsLog {
            val context = LoggerFactory.getILoggerFactory() as? LoggerContext
            if (context != null) return LogbackLanguageToolsLog(context)
            return LanguageToolsLog {
                log.warn("SLF4J does not log through logback; --verbose cannot show what dsl-tools logs")
                AutoCloseable {}
            }
        }
    }
}
