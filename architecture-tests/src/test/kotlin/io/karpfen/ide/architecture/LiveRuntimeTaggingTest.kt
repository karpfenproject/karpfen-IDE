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
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests against a real runtime run only on purpose. They carry the JUnit tag `live-runtime` through the `@LiveRuntime`
 * annotation of runtime-protocol's test fixtures. Every test task excludes the tag, except the opt-in task
 * `liveRuntimeTest` that build-logic's `karpfen.live-runtime-test` registers in the runtime modules. Nothing else
 * reaches that task. Besides that plugin, only the base convention plugin `karpfen.kotlin-jvm` names the task, to keep
 * the tag exclusion off it, and the build script of runtime-session names it, to order its live task after the one of
 * runtime-protocol. No other Gradle script wires it in. No CI workflow runs the task or the script that starts a
 * runtime in Docker. build-logic's `LiveRuntimeTestFunctionalTest` proves the wiring in a fixture build, and this test
 * guards the repository.
 *
 * A live test class sits in a runtime module, is named `Live…Test` and carries `@LiveRuntime`. Every class of a test
 * source set that is named this way must carry the annotation, so a forgotten annotation cannot make a live test run in
 * every build. The naming rule leaves out two kinds of class. Test fixtures are no tests, and the fixture
 * `LiveRuntimeUnderTest` is the live runtime of the scenarios, not a test. The classes of the architecture tests are
 * named after the rules they check.
 */
class LiveRuntimeTaggingTest {

    @Test
    fun `the live tag is live-runtime in build-logic and in the annotation of the live tests`() {
        val testTags = DocumentedFiles.all.single { it.relativePath.endsWith("/buildlogic/TestTags.kt") }
        assertTrue(LIVE_DECLARATION.containsMatchIn(testTags.text), "TestTags.LIVE_RUNTIME is no longer \"$LIVE_TAG\"")
        val annotation = DocumentedFiles.all.single { it.relativePath.endsWith(LIVE_ANNOTATION_FILE) }
        assertTrue(
            Regex("""@Tag\("$LIVE_TAG"\)\s*public annotation class $LIVE_ANNOTATION\b""").containsMatchIn(annotation.text),
            "${annotation.relativePath} no longer tags its annotation \"$LIVE_TAG\"",
        )
    }

    /**
     * Besides the live convention plugin, only the build script of runtime-host includes a tag. The tag belongs to its
     * own opt-in task for the tests against the local Docker engine, which build-logic's `TestTags.LIVE_DOCKER`
     * describes.
     */
    @Test
    fun `only the live convention plugin and runtime-host's Docker task include tags`() {
        val including = gradleScripts().filter { INCLUDE_TAGS.containsMatchIn(KotlinLexer.code(it.text)) }.map { it.relativePath }

        assertEquals(listOf(LIVE_PLUGIN, RUNTIME_HOST_SCRIPT), including)
    }

    @Test
    fun `no other Gradle script mentions the live task than the two convention plugins and runtime-session's ordering`() {
        val scripts = gradleScripts().associate { it.relativePath to it.text }
        assertTrue(
            scripts[RUNTIME_SESSION_SCRIPT]?.let(::mentionsLiveTask) == true,
            "runtime-session no longer orders its live task; this check would be vacuous",
        )

        val mentioning = scripts.filterKeys { it !in CONVENTION_PLUGINS }
            .mapValues { (path, text) -> if (path == RUNTIME_SESSION_SCRIPT) withoutOrderingBlock(text) else text }
            .filterValues(::mentionsLiveTask)
            .keys

        assertEquals(emptySet(), mentioning, "only the convention plugins and runtime-session's ordering block may name the live task")
    }

    @Test
    fun `no CI workflow runs the live tests or the script that starts a runtime`() {
        val workflows = File(Konsist.projectRootPath, ".github").walkTopDown().filter { it.isFile }.toList()
        assertTrue(workflows.isNotEmpty(), "no files below .github; this check would be vacuous")

        val mentioning = workflows.filter { file -> CI_FORBIDDEN.any { it in file.readText() } }.map { it.name }

        assertEquals(emptyList(), mentioning, "CI must not run $CI_FORBIDDEN")
    }

    @Test
    fun `live test classes sit in runtime modules, are named Live…Test and carry the annotation`() {
        val testFiles = KarpfenModules.moduleFiles.filter { it.sourceSetName != "main" && it.moduleDir != ARCHITECTURE_TESTS }

        val problems = problemsOf(
            testFiles,
            isRuntimeModule = { it.moduleDir.startsWith(RUNTIME_MODULES) },
            isTestSource = { it.sourceSetName == TEST_SOURCE_SET },
        )

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the naming and placement check finds every misplaced, misnamed and unannotated live test`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("Planted.kt"), PLANTED_TESTS)
        val planted = Konsist.scopeFromExternalDirectory(directory.toString()).files

        assertEquals(
            listOf(
                "Planted.kt: FakeLampScenariosTest carries @LiveRuntime but is not named Live…Test",
                "Planted.kt: LiveForgottenScenariosTest is named like a live test but lacks @LiveRuntime",
            ),
            problemsOf(planted, isRuntimeModule = { true }, isTestSource = { true }),
        )
        assertEquals(
            listOf(
                "Planted.kt: FakeLampScenariosTest carries @LiveRuntime but is not named Live…Test",
                "Planted.kt: FakeLampScenariosTest carries @LiveRuntime outside the runtime modules",
                "Planted.kt: LiveLampScenariosTest carries @LiveRuntime outside the runtime modules",
            ),
            problemsOf(planted, isRuntimeModule = { false }, isTestSource = { false }),
        )
    }

    /** Every Gradle script of the repository, build-logic's convention plugins included. */
    private fun gradleScripts(): List<RepositoryFile> = DocumentedFiles.all.filter { it.relativePath.endsWith(".gradle.kts") }

    /** True if [text] names the live task, by the constant of build-logic or by its value. */
    private fun mentionsLiveTask(text: String): Boolean = LIVE_TASK_NAMES.any { it in text }

    /** The [text] of runtime-session's build script without its block that orders the live tasks. */
    private fun withoutOrderingBlock(text: String): String {
        val code = KotlinLexer.code(text)
        val call = ORDERING_CALL.find(code) ?: return text
        val arguments = CodeRegions.callArguments(code, call.range.last + 1)
        return text.removeRange(call.range.first, arguments.last + 1)
    }

    /**
     * The violations of the live test rules among the classes of [files], sorted. A class annotated `@LiveRuntime`
     * violates them when it is not named `Live…Test`, and when it stands in a file that [isRuntimeModule] rejects. A
     * class named `Live…Test` violates them when it lacks the annotation in a file that [isTestSource] accepts.
     */
    private fun problemsOf(
        files: List<KoFileDeclaration>,
        isRuntimeModule: (KoFileDeclaration) -> Boolean,
        isTestSource: (KoFileDeclaration) -> Boolean,
    ): List<String> =
        files.flatMap { file ->
            file.classes(includeNested = true, includeLocal = true).flatMap { declaration ->
                val annotated = declaration.annotations.any { it.name == LIVE_ANNOTATION }
                val namedLive = LIVE_CLASS_NAME.matches(declaration.name)
                val where = file.path.substringAfterLast('/').substringAfterLast('\\')
                listOfNotNull(
                    "$where: ${declaration.name} carries @$LIVE_ANNOTATION but is not named Live…Test".takeIf { annotated && !namedLive },
                    "$where: ${declaration.name} carries @$LIVE_ANNOTATION outside the runtime modules"
                        .takeIf { annotated && !isRuntimeModule(file) },
                    "$where: ${declaration.name} is named like a live test but lacks @$LIVE_ANNOTATION"
                        .takeIf { namedLive && !annotated && isTestSource(file) },
                )
            }
        }.sorted()

    private companion object {
        /** The JUnit tag of the live tests, the value of `TestTags.LIVE_RUNTIME` in build-logic. */
        const val LIVE_TAG = "live-runtime"

        /** The simple name of the annotation that tags a live test class. */
        const val LIVE_ANNOTATION = "LiveRuntime"

        /** The file that declares the annotation, in runtime-protocol's test fixtures. */
        const val LIVE_ANNOTATION_FILE = "/src/testFixtures/kotlin/io/karpfen/ide/runtime/protocol/conformance/LiveRuntime.kt"

        /** The convention plugin that registers the live task. */
        const val LIVE_PLUGIN = "build-logic/src/main/kotlin/karpfen.live-runtime-test.gradle.kts"

        /** The two convention plugins that may name the live task: the live plugin and the base plugin that exempts it. */
        val CONVENTION_PLUGINS = setOf(LIVE_PLUGIN, "build-logic/src/main/kotlin/karpfen.kotlin-jvm.gradle.kts")

        /** The build script of runtime-host, which registers the opt-in task of the tests against the local Docker engine. */
        const val RUNTIME_HOST_SCRIPT = "runtime/runtime-host/build.gradle.kts"

        /** The build script that orders runtime-session's live task after runtime-protocol's. */
        const val RUNTIME_SESSION_SCRIPT = "runtime/runtime-session/build.gradle.kts"

        /** The module directory prefix of the runtime modules. */
        const val RUNTIME_MODULES = "runtime/"

        /** The module of the architecture tests themselves. */
        const val ARCHITECTURE_TESTS = "architecture-tests"

        /** The source set of the tests, as opposed to the test fixtures. */
        const val TEST_SOURCE_SET = "test"

        /** The names of the live task in a Gradle script: build-logic's constant and its value. */
        val LIVE_TASK_NAMES = listOf("LiveRuntimeTesting.TASK_NAME", "liveRuntimeTest")

        /** What a CI workflow must not mention: the live task, the URL property and the script that starts a runtime. */
        val CI_FORBIDDEN = listOf("liveRuntimeTest", "karpfen.runtime.url", "live-runtime.sh")

        /** The declaration of the live tag in build-logic's `TestTags`. */
        val LIVE_DECLARATION = Regex("""\bconst\s+val\s+LIVE_RUNTIME\s*:\s*String\s*=\s*"$LIVE_TAG"""")

        /** A call that includes JUnit tags. */
        val INCLUDE_TAGS = Regex("""\bincludeTags\s*\(""")

        /** The call that configures runtime-session's live task, followed by its ordering block. */
        val ORDERING_CALL = Regex("""\btasks\.named\(\s*LiveRuntimeTesting\.TASK_NAME\s*\)""")

        /** The name of a live test class. */
        val LIVE_CLASS_NAME = Regex("""Live\w*Test""")

        /** A test source with a live test, a live test whose annotation was forgotten, and a fake test tagged by mistake. */
        const val PLANTED_TESTS = """
            package planted

            import io.karpfen.ide.runtime.protocol.conformance.LiveRuntime

            abstract class LampScenarios

            @LiveRuntime
            class LiveLampScenariosTest : LampScenarios()

            class LiveForgottenScenariosTest : LampScenarios()

            @LiveRuntime
            class FakeLampScenariosTest : LampScenarios()
        """
    }
}
