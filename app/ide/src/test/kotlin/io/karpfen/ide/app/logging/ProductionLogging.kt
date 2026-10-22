package io.karpfen.ide.app.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertNotNull

/**
 * The production logging configuration of app/ide under test: the tests themselves log through the console-only
 * `logback-test.xml`, so these helpers configure a logger context of their own from `logback.xml` and capture what
 * would reach the console.
 */
internal object ProductionLogging {

    /**
     * A fresh logger context configured from the production `logback.xml` with [properties] as context properties. A
     * context of its own needs the MDC adapter that `LogbackServiceProvider` sets on the default one.
     */
    fun context(vararg properties: Pair<String, String>): LoggerContext {
        val context = LoggerContext()
        context.setMDCAdapter(LogbackMDCAdapter())
        properties.forEach { (key, value) -> context.putProperty(key, value) }
        val url = assertNotNull(ProductionLogging::class.java.classLoader.getResource("logback.xml"), "app/ide ships a logback.xml")
        JoranConfigurator().apply { setContext(context) }.doConfigure(url)
        return context
    }

    /**
     * Runs [block] with `System.out` and `System.err` captured and returns everything written to either: the console
     * appender of the configuration writes through `System.err` as it is when an event arrives.
     */
    fun capturingConsole(block: () -> Unit): String {
        val previousOut = System.out
        val previousErr = System.err
        val captured = ByteArrayOutputStream()
        val capture = PrintStream(captured, true, Charsets.UTF_8)
        System.setOut(capture)
        System.setErr(capture)
        try {
            block()
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
        return captured.toString(Charsets.UTF_8)
    }
}
