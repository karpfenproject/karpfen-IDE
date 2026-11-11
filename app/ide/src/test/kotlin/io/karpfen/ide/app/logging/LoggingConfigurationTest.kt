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
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.spi.FilterReply
import io.karpfen.ide.app.crash.IDE_LOG_FILE_NAME
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The IDE's logging configuration. The tests run on the module's console-only `logback-test.xml`. The production
 * `logback.xml` writes WARN and above to the console and INFO and above to `ide.log` below the IDE home. Where what
 * dsl-tools logs goes is the subject of `LanguageToolsRoutingTest`.
 */
class LoggingConfigurationTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the test classpath is configured by logback-test xml, not by the production logback xml`() {
        val context = assertIs<LoggerContext>(LoggerFactory.getILoggerFactory())

        val found = chosenResources(context)

        val chosen = assertNotNull(found.singleOrNull(), "exactly one configuration resource was chosen: $found")
        assertTrue(chosen.startsWith("Found resource [logback-test.xml] at ["), chosen)
        assertTrue(chosen.contains("/resources/test/logback-test.xml]"), "the module's own test resource: $chosen")
        assertTrue(javaClass.classLoader.getResource("logback.xml") != null, "the production file is on the classpath too")
    }

    @Test
    fun `the production configuration writes ide log below the IDE home property`() {
        val home = dir.resolve("home")
        val context = productionContext(SettingsLocations.HOME_PROPERTY to home.toString())

        context.getLogger("io.karpfen.ide.test").info("written to the file")
        context.stop()

        val log = SettingsLocations.logDirectory(home).resolve(IDE_LOG_FILE_NAME)
        val text = Files.readString(log)
        assertTrue("INFO  [" in text && "written to the file" in text, text)
    }

    @Test
    fun `without the IDE home property the log lives in the user's karpfen-ide directory`() {
        val userHome = dir.resolve("user")
        val context = productionContext("user.home" to userHome.toString())

        val file = assertIs<RollingFileAppender<*>>(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE")).file
        context.stop()

        assertEquals(userHome.resolve(".karpfen-ide").resolve("logs").resolve(IDE_LOG_FILE_NAME), Path.of(file))
    }

    @Test
    fun `the console shows WARN and above on standard error, the file INFO and above`() {
        val context = productionContext(SettingsLocations.HOME_PROPERTY to dir.toString())

        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val console = assertIs<ConsoleAppender<*>>(root.getAppender("CONSOLE"))
        val threshold = assertIs<ThresholdFilter>(console.copyOfAttachedFiltersList.single())

        assertEquals(Level.INFO, root.level)
        assertEquals("System.err", console.target)
        assertEquals(FilterReply.DENY, threshold.decide(loggingEvent(context, Level.INFO)))
        assertEquals(FilterReply.NEUTRAL, threshold.decide(loggingEvent(context, Level.WARN)))
        context.stop()
    }

    /**
     * The status messages in which logback's default configurator recorded the configuration resource it chose: "Found
     * resource [<name>] at [<url>]".
     */
    private fun chosenResources(context: LoggerContext): List<String> =
        context.statusManager.copyOfStatusList.map { it.message }.filter { it.startsWith("Found resource [") }

    /** A fresh logger context configured from the production `logback.xml` with [properties] as context properties. */
    private fun productionContext(vararg properties: Pair<String, String>): LoggerContext = ProductionLogging.context(*properties)

    /** A logging event of [level] from a logger of [context]. */
    private fun loggingEvent(context: LoggerContext, level: Level) =
        LoggingEvent(LoggingConfigurationTest::class.java.name, context.getLogger("io.karpfen.ide.test"), level, "message", null, null)
}
