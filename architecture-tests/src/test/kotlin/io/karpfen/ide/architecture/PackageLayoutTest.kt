package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue as kotlinAssertTrue

/**
 * Covers the package layout that every other architecture rule relies on: production code lives only in the modules of
 * [KarpfenModules.packagePrefixes] and within the package prefix of its module, no module contains Java sources, and
 * the scope of the rules holds the production code of every module but neither build-logic nor build output.
 */
class PackageLayoutTest {

    @Test
    fun `the scope contains the production code of every module`() {
        val modulesWithCode = KarpfenModules.mainFiles.map { it.moduleDir }.toSet()
        val missing = KarpfenModules.packagePrefixes.keys - modulesWithCode
        kotlinAssertTrue(missing.isEmpty(), "No production Kotlin files found for modules $missing; is the Konsist scope broken?")
    }

    @Test
    fun `production code lives only in known modules`() {
        KarpfenModules.sourceFiles
            .filter { it.sourceSetName == "main" }
            .assertTrue(
                additionalMessage = "Production Kotlin code must live in a module listed in KarpfenModules.packagePrefixes " +
                    "(add new modules there and to settings.gradle.kts).",
            ) { it.moduleDir in KarpfenModules.packagePrefixes }
    }

    @Test
    fun `production code lives in the package prefix of its module`() {
        KarpfenModules.mainFiles.assertTrue(
            additionalMessage = "Every file under <module>/src/main must be in the module's package prefix " +
                "(e.g. core/core-text -> io.karpfen.ide.core.text).",
        ) { file -> file.packageName.isWithin(KarpfenModules.packagePrefixes.getValue(file.moduleDir)) }
    }

    /**
     * All modules are plain Kotlin/JVM. `karpfen.kotlin-jvm` applies `java-library`, so a `java` source directory would
     * compile, but Konsist reads Kotlin files only: Java code would bypass every rule in this module.
     */
    @Test
    fun `modules contain no Java sources`() {
        val root = File(Konsist.projectRootPath)
        val sourceFiles = root.walkTopDown()
            .onEnter { dir -> dir == root || !(dir.name.startsWith(".") || dir.name == "build" || dir.isNestedBuild()) }
            .filter { it.isFile && "src" in it.relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1) }
            .toList()
        kotlinAssertTrue(sourceFiles.any { it.extension == "kt" }, "No Kotlin sources found under $root; this check would be vacuous")
        val javaFiles = sourceFiles.filter { it.extension == "java" }.map { it.relativeTo(root).invariantSeparatorsPath }
        kotlinAssertTrue(
            javaFiles.isEmpty(),
            "Java sources are not allowed (the architecture tests only check Kotlin; write Kotlin instead): $javaFiles",
        )
    }

    /** A directory below the root with its own settings file, e.g. build-logic/, is a separate build, not a module. */
    private fun File.isNestedBuild(): Boolean =
        resolve("settings.gradle.kts").isFile || resolve("settings.gradle").isFile

    /**
     * Neither build tooling nor generated code is product code. The raw Konsist scope contains build-logic, so the
     * filter in [KarpfenModules.sourceFiles] is what keeps it out. Build output contains Kotlin sources, too:
     * build-logic's generated kotlin-dsl accessors exist whenever this build runs, because build-logic is compiled
     * first, and Konsist itself must skip them.
     */
    @Test
    fun `build-logic and build output are excluded from the product scope`() {
        val rawScope = Konsist.scopeFromProject().files.map { it.projectPath.replace('\\', '/') }
        val productScope = KarpfenModules.sourceFiles.map { it.projectPath.replace('\\', '/') }

        val buildLogicInRawScope = rawScope.filter { it.startsWith("/build-logic/") }
        kotlinAssertTrue(buildLogicInRawScope.isNotEmpty(), "Konsist no longer sees build-logic/; this check would be vacuous")
        val buildLogicInProductScope = productScope.filter { it.startsWith("/build-logic/") }
        kotlinAssertTrue(buildLogicInProductScope.isEmpty(), "build-logic/ leaked into the product scope: $buildLogicInProductScope")

        val generatedSources = File(Konsist.projectRootPath, "build-logic/build").walkTopDown().filter { it.extension == "kt" }
        kotlinAssertTrue(generatedSources.any(), "No generated Kotlin sources under build-logic/build; this check would be vacuous")
        val buildOutputInRawScope = rawScope.filter { "/build/" in it }
        kotlinAssertTrue(buildOutputInRawScope.isEmpty(), "Build output is part of the Konsist scope: $buildOutputInRawScope")
    }
}
