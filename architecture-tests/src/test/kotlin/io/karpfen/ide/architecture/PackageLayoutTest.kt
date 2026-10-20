package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.verify.assertTrue
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
    fun `build-logic and build output are not part of the scanned product code`() {
        val leaked = KarpfenModules.moduleFiles.filter { file ->
            val path = file.projectPath.replace('\\', '/')
            path.startsWith("/build-logic/") || "/build/" in path
        }
        kotlinAssertTrue(leaked.isEmpty(), "Unexpected files in the product scope: ${leaked.map { it.projectPath }}")
    }
}
