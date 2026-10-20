package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue as kotlinAssertTrue

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

    @Test
    fun `build-logic and build output are excluded from the product scope`() {
        val rawScope = Konsist.scopeFromProject().files.map { it.projectPath.replace('\\', '/') }
        val productScope = KarpfenModules.sourceFiles.map { it.projectPath.replace('\\', '/') }

        // build-logic/ is part of the raw Konsist scope, so the filter in KarpfenModules.sourceFiles is what keeps it out.
        val buildLogicInRawScope = rawScope.filter { it.startsWith("/build-logic/") }
        kotlinAssertTrue(buildLogicInRawScope.isNotEmpty(), "Konsist no longer sees build-logic/; this check would be vacuous")
        val buildLogicInProductScope = productScope.filter { it.startsWith("/build-logic/") }
        kotlinAssertTrue(buildLogicInProductScope.isEmpty(), "build-logic/ leaked into the product scope: $buildLogicInProductScope")

        // Build output contains Kotlin sources (build-logic's generated kotlin-dsl accessors exist whenever this build
        // runs, because build-logic is compiled first), and Konsist itself must skip them.
        val generatedSources = File(Konsist.projectRootPath, "build-logic/build").walkTopDown().filter { it.extension == "kt" }
        kotlinAssertTrue(generatedSources.any(), "No generated Kotlin sources under build-logic/build; this check would be vacuous")
        val buildOutputInRawScope = rawScope.filter { "/build/" in it }
        kotlinAssertTrue(buildOutputInRawScope.isEmpty(), "Build output is part of the Konsist scope: $buildOutputInRawScope")
    }
}
