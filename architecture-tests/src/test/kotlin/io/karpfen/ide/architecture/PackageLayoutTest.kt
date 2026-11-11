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

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue as kotlinAssertTrue

/**
 * Covers the package layout that every other architecture rule relies on. Production code lives only in the modules of
 * [KarpfenModules.packagePrefixes] and within the package prefix of its module. No module contains Java sources. The
 * scope of the rules holds the production code of every module, but neither build-logic nor build output.
 *
 * One rule, [BuildOutput], tells build output from sources here, in the documentation style rules and in the
 * `.gitignore`. So a source package named `build` counts as sources everywhere. [OwnCode] leaves out the checkouts of
 * git submodules here, as it does everywhere. The files that the tests read at the root of the repository
 * ([RootFiles]) are inputs of their test task.
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
     * compile. Konsist reads Kotlin files only, so Java code would bypass every rule in this module.
     */
    @Test
    fun `modules contain no Java sources`() {
        val root = File(Konsist.projectRootPath)
        val sourceFiles = root.walkTopDown()
            .onEnter { dir -> dir == root || isScannedForJava(dir, root) }
            .filter { it.isFile && "src" in it.relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1) }
            .toList()
        kotlinAssertTrue(sourceFiles.any { it.extension == "kt" }, "No Kotlin sources found under $root; this check would be vacuous")
        val javaFiles = sourceFiles.filter { it.extension == "java" }.map { it.relativeTo(root).invariantSeparatorsPath }
        kotlinAssertTrue(
            javaFiles.isEmpty(),
            "Java sources are not allowed (the architecture tests only check Kotlin; write Kotlin instead): $javaFiles",
        )
    }

    /**
     * A submodule checkout is left out of the search for Java sources like everywhere else, also when it holds no
     * Gradle build of its own, which other repositories need not have.
     */
    @Test
    fun `a submodule checkout without a build of its own is not searched for Java sources`() {
        val root = File("/no-repository")
        val submodule = Submodules.paths.first()

        assertFalse(isScannedForJava(File(root, submodule), root))
        assertFalse(isScannedForJava(File(root, "$submodule/src/main/java"), root))
        kotlinAssertTrue(isScannedForJava(File(root, "core/core-text/src/main/java"), root))
    }

    /**
     * True for the directory [dir] below [root] if the search for Java sources enters it: a directory of this code base
     * ([OwnCode]) that is neither hidden nor a separate build.
     */
    private fun isScannedForJava(dir: File, root: File): Boolean =
        !dir.name.startsWith(".") && OwnCode.isOwnDirectory(dir.relativeTo(root).invariantSeparatorsPath) && !dir.isNestedBuild()

    /** True for a directory with its own settings file, such as `build-logic`, which is a separate build and no module. */
    private fun File.isNestedBuild(): Boolean =
        resolve("settings.gradle.kts").isFile || resolve("settings.gradle").isFile

    /**
     * Neither build tooling nor generated code is product code. The raw Konsist scope contains build-logic, so the
     * filter in [KarpfenModules.sourceFiles] is what keeps it out. Build output contains Kotlin sources too.
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
        val buildOutputInRawScope = rawScope.filter(BuildOutput::contains)
        kotlinAssertTrue(buildOutputInRawScope.isEmpty(), "Build output is part of the Konsist scope: $buildOutputInRawScope")
    }

    @Test
    fun `a source package named build is sources, a build directory outside the sources is build output`() {
        listOf("build", "build-logic/build", "core/core-text/build").forEach { kotlinAssertTrue(BuildOutput.isBuildDirectory(it), it) }
        listOf("core/core-text/src/main/kotlin/io/karpfen/ide/core/text/build", "core/core-text/builds").forEach {
            assertFalse(BuildOutput.isBuildDirectory(it), it)
        }
        kotlinAssertTrue(BuildOutput.contains("/build-logic/build/generated-sources/Accessors.kt"))
        kotlinAssertTrue(BuildOutput.contains("core/core-text/build/tmp/src/Generated.kt"))
        assertFalse(BuildOutput.contains("/core/core-text/src/main/kotlin/io/karpfen/ide/core/text/build/Planted.kt"))
    }

    /**
     * Git ignores build output only where Gradle writes it. Every pattern of the `.gitignore` that names a `build`
     * directory is anchored at the root, because an unanchored one would also ignore a source package named `build`.
     */
    @Test
    fun `git ignores only the build directories that Gradle writes`() {
        val patterns = RootFiles.read(RootFiles.GITIGNORE).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val buildPatterns = patterns.filter { BUILD_DIRECTORY in it.split('/') }
        kotlinAssertTrue(buildPatterns.isNotEmpty(), "The .gitignore ignores no build directory; this check would be vacuous")
        assertEquals(
            emptyList(),
            buildPatterns.filterNot { it.startsWith("/") },
            "These .gitignore patterns are not anchored at the root, so they would also ignore a source package named build",
        )
    }

    /**
     * Every file the tests read at the root of the repository is an input of their test task, so that changing it runs
     * the tests again instead of serving their last result from the build cache.
     */
    @Test
    fun `the files the tests read at the root are inputs of their test task`() {
        val script = File(Konsist.projectRootPath, "architecture-tests/build.gradle.kts").readText()
        val declared = ROOT_INPUTS.find(script)?.groupValues?.get(1)
        kotlinAssertTrue(declared != null, "The build script of the architecture tests declares no root files as inputs")

        assertEquals(emptyList(), RootFiles.all.filterNot { "\"$it\"" in declared })
    }

    private companion object {
        /** The name of the directories that hold build output. */
        const val BUILD_DIRECTORY = "build"

        /** The root files that the build script of the architecture tests declares as inputs of their test task. */
        val ROOT_INPUTS = Regex("""val rootFilesRead: List<String> = listOf\(([^)]*)\)""")
    }
}
