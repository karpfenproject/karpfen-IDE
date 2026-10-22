package io.karpfen.ide.tools.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CLI's logging configuration: the tests run on the module's console-only `logback-test.xml`, while the production
 * `logback.xml` logs WARN and above to standard error only, so the report on standard output stays clean.
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
        context.stop()
    }

    /**
     * The status messages in which logback's default configurator recorded the configuration resource it chose: "Found
     * resource [<name>] at [<url>]".
     */
    private fun chosenResources(context: LoggerContext): List<String> =
        context.statusManager.copyOfStatusList.map { it.message }.filter { it.startsWith("Found resource [") }
}
