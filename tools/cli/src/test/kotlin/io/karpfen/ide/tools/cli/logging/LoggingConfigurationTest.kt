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
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CLI's logging configuration. The tests run on the module's console-only `logback-test.xml`. The production
 * `logback.xml` logs WARN and above to standard error only, so the report on standard output stays clean. Both turn
 * what dsl-tools logs off and let its logger pass events on to the console, the way `--verbose` shows them.
 */
class LoggingConfigurationTest {

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
    fun `the production configuration logs WARN and above to standard error only`() {
        val context = LoggerContext()
        val url = assertNotNull(javaClass.classLoader.getResource("logback.xml"), "tools/cli ships a logback.xml")
        JoranConfigurator().apply { setContext(context) }.doConfigure(url)

        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val appenders = root.iteratorForAppenders().asSequence().toList()

        assertEquals(Level.WARN, root.level)
        val console = assertIs<ConsoleAppender<*>>(appenders.single())
        assertEquals("System.err", console.target)
        assertDslToolsOffAndPassingEventsOn(context)
        context.stop()
    }

    @Test
    fun `the test configuration turns dsl-tools off like the production one`() {
        assertDslToolsOffAndPassingEventsOn(assertIs<LoggerContext>(LoggerFactory.getILoggerFactory()))
    }

    /**
     * Asserts that the dsl-tools logger of [context] is off, has no appender of its own and passes its events on, so
     * raising its level shows them on the root logger's console.
     */
    private fun assertDslToolsOffAndPassingEventsOn(context: LoggerContext) {
        val dslTools = context.getLogger(DslToolsLoggers.PARENT)
        assertEquals(Level.OFF, dslTools.level)
        assertTrue(dslTools.isAdditive)
        assertFalse(dslTools.iteratorForAppenders().hasNext())
    }

    /**
     * The status messages in which logback's default configurator recorded the configuration resource it chose: "Found
     * resource [<name>] at [<url>]".
     */
    private fun chosenResources(context: LoggerContext): List<String> =
        context.statusManager.copyOfStatusList.map { it.message }.filter { it.startsWith("Found resource [") }
}
