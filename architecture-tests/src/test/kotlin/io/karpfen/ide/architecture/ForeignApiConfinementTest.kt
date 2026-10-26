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
package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adapters isolate foreign APIs: ANTLR and the parsers that dsl-tools generates from its grammars are only reached from
 * the ANTLR adapter package [KarpfenModules.ANTLR_ADAPTER_PACKAGE]; everything else of dsl-tools except its semantic
 * model only from the dsl adapter package [KarpfenModules.DSL_ADAPTER_PACKAGE], so no other package sees ANTLR types,
 * dsl-tools readers, diagnostics or printers; and logback only from the logging packages of the application and the
 * command-line tool ([LOGGING_PACKAGES]). The semantic model of dsl-tools ([DSL_TOOLS_SEMANTIC_MODEL]) stays usable
 * everywhere. The dsl-tools rule is an allow-list: a package that dsl-tools adds later is confined until it is added to
 * the semantic model on purpose. The rules apply to the main, test and testFixtures sources of every module, and a
 * fully qualified reference in code counts like an import.
 */
class ForeignApiConfinementTest {

    private companion object {
        /** The root package of the ANTLR runtime. */
        const val ANTLR_RUNTIME = "org.antlr"

        /** The root package of dsl-tools. */
        const val DSL_TOOLS = "io.karpfen.dsl"

        /** The package of the lexers, parsers and visitors that dsl-tools generates from its grammars at build time. */
        const val GENERATED_PARSERS = "$DSL_TOOLS.grammar"

        /** The dsl-tools package of the readers, the entry points through which the IDE reads its languages. */
        const val DSL_TOOLS_READERS = "$DSL_TOOLS.reader"

        /** The root package of logback, the logging backend of the application and the command-line tool. */
        const val LOGBACK = "ch.qos.logback"

        /** The packages that only the ANTLR adapter package may use. */
        val ANTLR_PACKAGES = listOf(ANTLR_RUNTIME, GENERATED_PARSERS)

        /** The semantic model of dsl-tools: metamodels, models and state machines, which every package may use. */
        val DSL_TOOLS_SEMANTIC_MODEL = listOf("$DSL_TOOLS.meta", "$DSL_TOOLS.instance", "$DSL_TOOLS.states")

        /** The packages that may use logback: the logging packages of the application and the command-line tool. */
        val LOGGING_PACKAGES = listOf("io.karpfen.ide.app.logging", "io.karpfen.ide.tools.cli.logging")

        /** True if [name] lies within ANTLR or the generated parsers, which only the ANTLR adapter may use. */
        fun usesAntlr(name: String): Boolean = name.isWithinAny(ANTLR_PACKAGES)

        /**
         * True if [name] lies within dsl-tools but neither within its semantic model nor within the generated parsers,
         * which the ANTLR rule confines: the readers, diagnostics, source positions, printers, converters, builders,
         * file parsers and every package dsl-tools adds later, which only the dsl adapter may use.
         */
        fun usesAdaptedDslTools(name: String): Boolean =
            name.isWithin(DSL_TOOLS) && !name.isWithinAny(DSL_TOOLS_SEMANTIC_MODEL + GENERATED_PARSERS)

        /** True if [name] lies within logback, which only the logging packages may use. */
        fun usesLogback(name: String): Boolean = name.isWithin(LOGBACK)
    }

    @Test
    fun `only the ANTLR adapter package uses ANTLR and the generated dsl-tools parsers`() {
        assertConfined(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(KarpfenModules.ANTLR_ADAPTER_PACKAGE) },
            rule = "Only ${KarpfenModules.ANTLR_ADAPTER_PACKAGE}.. may use ${ANTLR_PACKAGES.joinToString { "$it.." }}",
        ) { name -> usesAntlr(name) }
    }

    @Test
    fun `only the dsl adapter package uses dsl-tools beyond its semantic model`() {
        assertConfined(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(KarpfenModules.DSL_ADAPTER_PACKAGE) },
            rule = "Only ${KarpfenModules.DSL_ADAPTER_PACKAGE}.. may use $DSL_TOOLS.. beyond its semantic model " +
                "(${DSL_TOOLS_SEMANTIC_MODEL.joinToString { "$it.." }}); use its DslReader adapter",
        ) { name -> usesAdaptedDslTools(name) }
    }

    @Test
    fun `only the logging packages of the application and the command-line tool use logback`() {
        assertConfined(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithinAny(LOGGING_PACKAGES) },
            rule = "Only ${LOGGING_PACKAGES.joinToString { "$it.." }} may use $LOGBACK..; elsewhere log through SLF4J",
        ) { name -> usesLogback(name) }
    }

    /**
     * The internal adapter API hands out foreign types (e.g. `LexedText.tokens: List<Token>`), which Kotlin's type
     * inference lets a caller use without importing org.antlr, so the entry points themselves are confined: only the
     * adapter packages and the registry (the Abstract Factory wiring them) may depend on them. Other modules reach the
     * public `DslReader` of the dsl package directly.
     */
    @Test
    fun `inside core-document only the registry wires the adapter packages`() {
        val registry = "io.karpfen.ide.core.document.registry"
        val graph = PackageGraph.ofModule("core/core-document")
        listOf(KarpfenModules.ANTLR_ADAPTER_PACKAGE, KarpfenModules.DSL_ADAPTER_PACKAGE).forEach { adapter ->
            val violations = graph.edges
                .filterKeys { source -> !source.isWithin(adapter) && source != registry }
                .flatMap { (source, targets) -> targets.filter { it.isWithin(adapter) }.map { "$source -> $it" } }
            assertTrue(violations.isEmpty(), "only $adapter.. and $registry may depend on $adapter..: $violations")
            assertTrue(
                graph.edges[registry].orEmpty().any { it.isWithin(adapter) },
                "expected $registry -> $adapter..; is the rule still aimed at the right packages?",
            )
        }
    }

    /**
     * Guards against vacuous rules: if an adapter package or a foreign package were renamed, the confinement rules above
     * would pass trivially, so each adapter, the logging packages included, must hold production code that uses the
     * packages its rule confines.
     */
    @Test
    fun `the adapter packages exist and are where the foreign APIs are used`() {
        val antlrFiles = KarpfenModules.filesInPackage(KarpfenModules.ANTLR_ADAPTER_PACKAGE)
        assertTrue(antlrFiles.any { it.sourceSetName == "main" }, "no production code in ${KarpfenModules.ANTLR_ADAPTER_PACKAGE}")
        listOf(ANTLR_RUNTIME, GENERATED_PARSERS).forEach { foreign ->
            assertTrue(
                antlrFiles.any { file -> file.imports.any { it.name.isWithin(foreign) } },
                "nothing in ${KarpfenModules.ANTLR_ADAPTER_PACKAGE} uses $foreign; is the rule still aimed at the right package?",
            )
        }
        val dslFiles = KarpfenModules.filesInPackage(KarpfenModules.DSL_ADAPTER_PACKAGE)
        assertTrue(dslFiles.any { it.sourceSetName == "main" }, "no production code in ${KarpfenModules.DSL_ADAPTER_PACKAGE}")
        assertTrue(
            dslFiles.any { file -> file.sourceSetName == "main" && file.imports.any { it.name.isWithin(DSL_TOOLS_READERS) } },
            "nothing in ${KarpfenModules.DSL_ADAPTER_PACKAGE} uses $DSL_TOOLS_READERS; is the rule still aimed at the right package?",
        )
        LOGGING_PACKAGES.forEach { logging ->
            assertTrue(
                KarpfenModules.filesInPackage(logging)
                    .any { file -> file.sourceSetName == "main" && file.imports.any { it.name.isWithin(LOGBACK) } },
                "nothing in the production code of $logging uses $LOGBACK; is the rule still aimed at the right package?",
            )
        }
    }

    /**
     * Covers [usesAntlr] and [usesAdaptedDslTools]: the generated parsers count for the ANTLR rule, and every other
     * package of dsl-tools beyond its semantic model counts for the dsl rule, the printers and the examples included,
     * and so do packages that merely share a prefix with the semantic model, in imports and in fully qualified
     * references in code; the semantic model and packages outside dsl-tools that merely share its prefix do not.
     */
    @Test
    fun `the rules recognize the dsl-tools packages and leave the semantic model alone`() {
        assertTrue(usesAntlr("io.karpfen.dsl.grammar.kstates.KstatesLexer"))
        assertTrue(usesAntlr("io.karpfen.dsl.grammar.kmeta.*"))
        assertTrue(usesAntlr("org.antlr.v4.runtime.Token"))
        val adapted = listOf(
            "io.karpfen.dsl.reader.kmodel.KmodelReader",
            "io.karpfen.dsl.reader.ParseResult",
            "io.karpfen.dsl.diagnostics.DiagnosticCode",
            "io.karpfen.dsl.source.SourceMap",
            "io.karpfen.dsl.printer.kmeta.KmetaPrinter",
            "io.karpfen.dsl.printer.kstates.*",
            "io.karpfen.dsl.examples.printObject",
            "io.karpfen.dsl.dsl.textual.KmetaDSLConverter",
            "io.karpfen.dsl.dsl.functional.MetamodelBuilder",
            "io.karpfen.dsl.parser.KmetaFileParser",
            "io.karpfen.dsl.grammarguide.Example",
            "io.karpfen.dsl.metadata.Example",
        )
        assertEquals(emptyList(), adapted.filterNot { usesAdaptedDslTools(it) }, "confined to the dsl adapter")
        assertEquals(emptyList(), adapted.filter { usesAntlr(it) }, "not the generated parsers")
        assertFalse(usesAdaptedDslTools("io.karpfen.dsl.grammar.kmeta.KmetaParser"), "the ANTLR rule confines the generated parsers")

        val usableEverywhere = listOf(
            "io.karpfen.dsl.meta.Metamodel",
            "io.karpfen.dsl.instance.Model",
            "io.karpfen.dsl.states.StateMachine",
            "io.karpfen.dslx.Example",
            "io.karpfen.ide.core.document.dsl.DslReader",
        )
        assertTrue(
            usableEverywhere.none { usesAntlr(it) || usesAdaptedDslTools(it) },
            "the semantic model and packages that merely share a prefix must stay usable: $usableEverywhere",
        )

        val inCode = SourceReferences.qualifiedReferences(
            "val lexer = io.karpfen.dsl.grammar.kstates.KstatesLexer(input)\n" +
                "val printer = io.karpfen.dsl.printer.kstates.KstatesPrinter()",
        ).map { it.name }
        assertTrue(inCode.any { usesAntlr(it) }, "a fully qualified generated parser in code must count: $inCode")
        assertTrue(inCode.any { usesAdaptedDslTools(it) }, "a fully qualified printer in code must count: $inCode")
    }

    /** Covers [usesLogback]: the packages of logback count, SLF4J and packages that merely share a prefix do not. */
    @Test
    fun `the logback rule recognizes logback and leaves SLF4J alone`() {
        assertTrue(usesLogback("ch.qos.logback.classic.LoggerContext"))
        assertTrue(usesLogback("ch.qos.logback.core.read.ListAppender"))
        val others = listOf("org.slf4j.LoggerFactory", "ch.qos.logbackx.Other", "ch.qos.reload4j.Logger")
        assertEquals(emptyList(), others.filter(::usesLogback))
    }

    /**
     * Fails if an import or a fully qualified reference in [files] is forbidden by [isForbidden]. Inline, so that
     * Konsist, which derives the test name from the call stack, reports and suppresses by the test method name.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun assertConfined(
        files: List<KoFileDeclaration>,
        rule: String,
        noinline isForbidden: (name: String) -> Boolean,
    ) {
        assertTrue(files.isNotEmpty(), "Rule '$rule' matched no files; is the Konsist scope broken?")
        files.flatMap { it.imports }.assertFalse(additionalMessage = rule) { import -> isForbidden(import.name) }

        val qualified: Map<KoFileDeclaration, List<QualifiedReference>> = files
            .associateWith { file -> file.qualifiedReferences.filter { isForbidden(it.name) } }
            .filterValues { it.isNotEmpty() }
        files.assertFalse(
            additionalMessage = "$rule (fully qualified references count as imports: " +
                qualified.entries.joinToString { (file, references) -> "${file.projectPath}: ${references.joinToString()}" } + ")",
        ) { file -> file in qualified }
    }
}
