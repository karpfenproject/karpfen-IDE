package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Adapters isolate foreign APIs (spec §0, §1a): ANTLR and the generated dsl-tools parsers are only reached from
 * [KarpfenModules.ANTLR_ADAPTER_PACKAGE], the dsl-tools converters and parser classes only from
 * [KarpfenModules.DSL_ADAPTER_PACKAGE]. Applies to main, test and testFixtures sources of every module.
 *
 * The dsl-tools packages have short top-level names (`kmeta`, `parser`, …) that can also be local variable names, so
 * a fully qualified reference counts only when the segment after the package starts with an upper-case letter (a
 * class, e.g. `kstates.KstatesParser`); imports always count.
 */
class ForeignApiConfinementTest {

    private companion object {
        const val ANTLR_RUNTIME = "org.antlr"

        /** Packages of the ANTLR-generated lexers, parsers and visitors shipped in dsl-tools. */
        val GENERATED_PARSER_PACKAGES = listOf("kmeta", "kmodel", "kstates")

        /** dsl-tools packages with the converters (`dsl.textual`), builders (`dsl.functional`) and hand-written parsers. */
        val DSL_TOOLS_CONVERTER_PACKAGES = listOf("dsl", "parser")
    }

    @Test
    fun `only the ANTLR adapter package uses ANTLR and the generated dsl-tools parsers`() {
        assertConfined(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(KarpfenModules.ANTLR_ADAPTER_PACKAGE) },
            rule = "Only ${KarpfenModules.ANTLR_ADAPTER_PACKAGE}.. may use $ANTLR_RUNTIME.. and the generated parsers " +
                "${GENERATED_PARSER_PACKAGES.joinToString { "$it.." }} (spec §0)",
        ) { name, isImport -> name.isWithin(ANTLR_RUNTIME) || name.isForeignClassUse(GENERATED_PARSER_PACKAGES, isImport) }
    }

    @Test
    fun `only the dsl adapter package uses the dsl-tools converters and parser classes`() {
        assertConfined(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(KarpfenModules.DSL_ADAPTER_PACKAGE) },
            rule = "Only ${KarpfenModules.DSL_ADAPTER_PACKAGE}.. may use the dsl-tools converters and parsers " +
                "(${DSL_TOOLS_CONVERTER_PACKAGES.joinToString { "$it.." }}); use its DslConverter adapter (spec §1a)",
        ) { name, isImport -> name.isForeignClassUse(DSL_TOOLS_CONVERTER_PACKAGES, isImport) }
    }

    @Test
    fun `the adapter packages exist and are where the foreign APIs are used`() {
        // Guards against vacuous rules: if a package were renamed, the confinement rules above would pass trivially.
        val antlrFiles = KarpfenModules.filesInPackage(KarpfenModules.ANTLR_ADAPTER_PACKAGE)
        assertTrue(antlrFiles.any { it.sourceSetName == "main" }, "no production code in ${KarpfenModules.ANTLR_ADAPTER_PACKAGE}")
        assertTrue(
            antlrFiles.any { file -> file.imports.any { it.name.isWithin(ANTLR_RUNTIME) } },
            "nothing in ${KarpfenModules.ANTLR_ADAPTER_PACKAGE} uses $ANTLR_RUNTIME; is the rule still aimed at the right package?",
        )
        val dslFiles = KarpfenModules.filesInPackage(KarpfenModules.DSL_ADAPTER_PACKAGE)
        assertTrue(dslFiles.any { it.sourceSetName == "main" }, "no production code in ${KarpfenModules.DSL_ADAPTER_PACKAGE}")
        assertTrue(
            dslFiles.any { file -> file.imports.any { it.name.isWithin("dsl.textual") } },
            "nothing in ${KarpfenModules.DSL_ADAPTER_PACKAGE} uses dsl.textual; is the rule still aimed at the right package?",
        )
    }

    @Test
    fun `foreign class uses are recognized in imports and qualified references`() {
        assertTrue("kstates.KstatesLexer".isForeignClassUse(GENERATED_PARSER_PACKAGES, isImport = false))
        assertTrue("kmeta.*".isForeignClassUse(GENERATED_PARSER_PACKAGES, isImport = true))
        assertTrue("dsl.textual.KmetaDSLConverter".isForeignClassUse(DSL_TOOLS_CONVERTER_PACKAGES, isImport = false))
        assertTrue("parser.KmetaFileParser".isForeignClassUse(DSL_TOOLS_CONVERTER_PACKAGES, isImport = false))
        // Member access on local variables named like a package is not a use.
        assertTrue(!"parser.parse".isForeignClassUse(DSL_TOOLS_CONVERTER_PACKAGES, isImport = false))
        assertTrue(!"kmeta.text".isForeignClassUse(GENERATED_PARSER_PACKAGES, isImport = false))
        // dsl-tools' semantic model packages (meta, instance, states) stay usable everywhere.
        assertTrue(!"states.StateMachine".isForeignClassUse(GENERATED_PARSER_PACKAGES, isImport = false))
    }

    /** Fails if an import or a fully qualified reference in [files] is forbidden by [isForbidden]. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun assertConfined(
        files: List<KoFileDeclaration>,
        rule: String,
        noinline isForbidden: (name: String, isImport: Boolean) -> Boolean,
    ) {
        assertTrue(files.isNotEmpty(), "Rule '$rule' matched no files; is the Konsist scope broken?")
        files.flatMap { it.imports }.assertFalse(additionalMessage = rule) { import -> isForbidden(import.name, true) }

        val qualified: Map<KoFileDeclaration, List<QualifiedReference>> = files
            .associateWith { file -> file.qualifiedReferences.filter { isForbidden(it.name, false) } }
            .filterValues { it.isNotEmpty() }
        files.assertFalse(
            additionalMessage = "$rule (fully qualified references count as imports: " +
                qualified.entries.joinToString { (file, references) -> "${file.projectPath}: ${references.joinToString()}" } + ")",
        ) { file -> file in qualified }
    }
}

/**
 * True if this import or qualified name uses one of the foreign [packages]: an import anywhere below them, or a
 * qualified reference whose first segment after the lower-case package segments starts with an upper-case letter
 * (a class such as `kstates.KstatesParser` or `dsl.textual.KmetaDSLConverter`, see the class comment).
 */
internal fun String.isForeignClassUse(packages: Collection<String>, isImport: Boolean): Boolean {
    val root = packages.firstOrNull { isWithin(it) } ?: return false
    if (isImport) return true
    val segments = removePrefix(root).split('.').filter { it.isNotEmpty() }
    val firstNonPackageSegment = segments.firstOrNull { !it.first().isLowerCase() } ?: return false
    return firstNonPackageSegment.first().isUpperCase()
}
