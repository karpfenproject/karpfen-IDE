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
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import org.slf4j.LoggerFactory
import kotlin.test.assertIs

/**
 * The logger context of the test JVM, which the application's default routing of what dsl-tools logs attaches to. It
 * serves tests outside this package, which log through SLF4J only.
 */
internal object ApplicationLogging {

    /**
     * Runs [block] with the dsl-tools loggers of the test JVM's logger context set up as the production configuration
     * has them. They log at DEBUG and pass nothing on to the test console. Both settings are restored afterwards.
     */
    suspend fun <T> withDslToolsAtDebug(block: suspend () -> T): T {
        val logger = assertIs<LoggerContext>(LoggerFactory.getILoggerFactory()).getLogger(DslToolsLoggers.PARENT)
        val level = logger.level
        val additive = logger.isAdditive
        logger.level = Level.DEBUG
        logger.isAdditive = false
        try {
            return block()
        } finally {
            logger.level = level
            logger.isAdditive = additive
        }
    }
}
