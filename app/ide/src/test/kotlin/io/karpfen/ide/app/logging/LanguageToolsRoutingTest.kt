package io.karpfen.ide.app.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.karpfen.ide.app.crash.IDE_LOG_FILE_NAME
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where what dsl-tools logs goes in the IDE, through the production `logback.xml` and [LanguageToolsLogRouting]: every
 * event reaches the Language Tools Output and `dsl-tools.log`, never the console; its warnings and errors are forwarded
 * to `ide.log` as well; and the IDE's own events keep their way to the console and `ide.log` without entering the
 * output of the language tools.
 */
class LanguageToolsRoutingTest {

    @TempDir
    lateinit var dir: Path

    private val home: Path get() = dir.resolve("home")
    private val output = RingOutputBuffer()
    private val context: LoggerContext by lazy { ProductionLogging.context(SettingsLocations.HOME_PROPERTY to home.toString()) }

    private val converter: Logger get() = context.getLogger("${DslToolsLoggers.PARENT}.dsl.textual.KmetaDSLConverter")
    private val fileParser: Logger get() = context.getLogger("${DslToolsLoggers.PARENT}.parser.KmetaFileParser")

    @AfterTest
    fun stopLogging() {
        context.stop()
    }

    /** The text of the log file [name] in the IDE home's log directory, after the context wrote and closed it. */
    private fun logText(name: String): String {
        context.stop()
        val file = SettingsLocations.logDirectory(home).resolve(name)
        return if (Files.exists(file)) Files.readString(file) else ""
    }

    /** Runs [block] with the output routed and the console captured; returns what reached the console. */
    private fun routed(block: () -> Unit): String =
        ProductionLogging.capturingConsole { LanguageToolsLogRouting(context).attach(output).use { block() } }

    @Test
    fun `what dsl-tools logs reaches the output and dsl-tools log, but neither the console nor the IDE log`() {
        val console = routed {
            converter.debug("Type {}: {}", "Robot", "a robot")
            converter.info("converted")
        }

        assertEquals(
            listOf(OutputLevel.DEBUG to "Type Robot: a robot", OutputLevel.INFO to "converted"),
            output.snapshot().lines.map { it.level to it.message },
        )
        assertEquals(converter.name, output.snapshot().lines.first().logger)
        assertEquals("", console)
        val dslToolsLog = logText(DSL_TOOLS_LOG)
        assertTrue("DEBUG" in dslToolsLog && "Type Robot: a robot" in dslToolsLog, dslToolsLog)
        assertFalse("Type Robot" in logText(IDE_LOG_FILE_NAME))
    }

    @Test
    fun `warnings and errors of dsl-tools are forwarded to the IDE log, and still not shown on the console`() {
        val console = routed {
            fileParser.warn("KMeta syntax error at line 1:5")
            fileParser.error("conversion failed", IllegalStateException("broken model"))
        }

        assertEquals(listOf(OutputLevel.WARNING, OutputLevel.ERROR), output.snapshot().lines.map { it.level })
        assertTrue("IllegalStateException: broken model" in output.snapshot().lines.last().message, "with the stack trace")
        assertEquals("", console)
        val ideLog = logText(IDE_LOG_FILE_NAME)
        assertTrue("WARN " in ideLog && "KMeta syntax error at line 1:5" in ideLog, ideLog)
        assertTrue("ERROR" in ideLog && "conversion failed" in ideLog, ideLog)
        assertTrue("KMeta syntax error at line 1:5" in logText(DSL_TOOLS_LOG))
    }

    @Test
    fun `the IDE's own events keep to the console and the IDE log and stay out of the output`() {
        val ide = context.getLogger("io.karpfen.ide.core.workspace.files.WorkspaceScanner")

        val console = routed {
            ide.warn("Cannot list directory /x")
            ide.info("scanned")
        }

        assertEquals(emptyList(), output.snapshot().lines)
        assertTrue("Cannot list directory /x" in console, console)
        assertFalse("scanned" in console)
        val ideLog = logText(IDE_LOG_FILE_NAME)
        assertTrue("Cannot list directory /x" in ideLog && "scanned" in ideLog, ideLog)
        assertEquals("", logText(DSL_TOOLS_LOG))
    }

    @Test
    fun `dsl-tools logs at DEBUG to its own file and the IDE log, without passing events on to the console`() {
        val logger = context.getLogger(DslToolsLoggers.PARENT)

        assertEquals(Level.DEBUG, logger.level)
        assertFalse(logger.isAdditive, "nothing of dsl-tools reaches the root logger's console")
        assertEquals(setOf("DSL_TOOLS_FILE", "FILE"), logger.iteratorForAppenders().asSequence().map { it.name }.toSet())
    }

    @Test
    fun `after the routing is closed, nothing more reaches the output`() {
        val routing = LanguageToolsLogRouting(context).attach(output)
        converter.debug("routed")

        routing.close()
        converter.debug("not routed")

        assertEquals(listOf("routed"), output.snapshot().lines.map { it.message })
        assertEquals(null, context.getLogger(DslToolsLoggers.PARENT).getAppender(LanguageToolsLogRouting.APPENDER_NAME))
    }

    private companion object {
        /** The rolling file of what dsl-tools logs, next to the IDE log. */
        const val DSL_TOOLS_LOG = "dsl-tools.log"
    }
}
