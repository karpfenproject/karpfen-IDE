package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.dsl.textual.KmetaDSLConverter
import io.karpfen.dsl.dsl.textual.KmodelDSLConverter
import io.karpfen.dsl.dsl.textual.KstatesDSLConverter
import io.karpfen.dsl.parser.KmetaFileParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DslToolsLoggers]: dsl-tools names its loggers after its classes, so the converters and file parsers log below the
 * parent logger, and only names below it count as dsl-tools loggers.
 */
class DslToolsLoggersTest {

    @Test
    fun `the classes that log in dsl-tools lie below the parent logger`() {
        listOf(KmetaDSLConverter::class, KmodelDSLConverter::class, KstatesDSLConverter::class, KmetaFileParser::class)
            .map { it.java.name }
            .forEach { name -> assertTrue(DslToolsLoggers.isDslToolsLogger(name), name) }
    }

    @Test
    fun `only the parent and the loggers below it are dsl-tools loggers`() {
        assertTrue(DslToolsLoggers.isDslToolsLogger(DslToolsLoggers.PARENT))
        assertTrue(DslToolsLoggers.isDslToolsLogger("io.karpfen.dsl.parser.LoggingSyntaxErrorListener"))
        listOf("io.karpfen.dslx.Other", "io.karpfen.ide.core.document.dsl.DefaultDslConverter", "io.karpfen", "")
            .forEach { name -> assertFalse(DslToolsLoggers.isDslToolsLogger(name), name) }
    }
}
