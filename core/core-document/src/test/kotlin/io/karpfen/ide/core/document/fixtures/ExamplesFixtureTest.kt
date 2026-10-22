package io.karpfen.ide.core.document.fixtures

import io.karpfen.ide.core.document.KarpfenLanguage
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves that the shared test fixtures (example files, mutations, synthetic documents, environment descriptors) load and
 * behave deterministically. Uses only the fixture helpers and value types, never the tokenizers, parsers or sessions.
 */
class ExamplesFixtureTest {

    @Test
    fun `every indexed example file loads with its language`() {
        val files = Examples.files()

        assertEquals(21, files.size, "example files: ${files.map { it.path }}")
        assertEquals(
            mapOf(KarpfenLanguage.KMETA to 10, KarpfenLanguage.KMODEL to 6, KarpfenLanguage.KSTATES to 5),
            files.groupingBy { it.language }.eachCount(),
        )
        assertEquals(
            mapOf(ExampleOrigin.KARPFEN_RUNTIME to 16, ExampleOrigin.KARPFEN_DSL_TOOLS to 5),
            files.groupingBy { it.origin }.eachCount(),
        )
        files.forEach { file -> assertTrue(file.text.isNotBlank(), "$file is empty") }
        assertEquals(5, Examples.files(KarpfenLanguage.KSTATES).size)
        assertEquals("EVENTS.kmeta", Examples.file("karpfen-runtime/single_robot_example/EVENTS.kmeta").fileName)
    }

    /** Gradle runs the tests with the module directory as working directory, so the fixture path is relative to it. */
    @Test
    fun `the index lists exactly the karpfen files of the fixture directory`() {
        val root = Path.of("src/testFixtures/resources/examples")
        assertTrue(Files.isDirectory(root), "fixture directory not found from ${Path.of("").toAbsolutePath()}")
        val onDisk = Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension in setOf("kmeta", "kmodel", "kstates") }
                .map { root.relativize(it).invariantSeparatorsPathString }
                .toList()
        }
        assertEquals(onDisk.sorted(), Examples.indexedPaths().sorted())
        assertTrue(Files.isRegularFile(root.resolve("NOTICE.md")), "NOTICE.md is missing")
    }

    @Test
    fun `examples group into one context per directory, each with a metamodel`() {
        val contexts = Examples.contexts()

        assertEquals(
            listOf(
                "karpfen-dsl-tools",
                "karpfen-dsl-tools/statemachine_full_example",
                "karpfen-runtime/autonomous_car_example",
                "karpfen-runtime/delivery_drone_example",
                "karpfen-runtime/drone_swarm_example",
                "karpfen-runtime/single_robot_example",
            ),
            contexts.map { it.directory },
        )
        contexts.forEach { context ->
            val metamodel = assertNotNull(context.metamodel, "$context has no metamodel")
            assertTrue(!metamodel.isEventsMetamodel)
            assertEquals(1, context.models.size, "$context models")
        }
        contexts.filter { it.directory.startsWith("karpfen-runtime/") }.forEach { context ->
            assertNotNull(context.events, "$context has no EVENTS.kmeta")
            assertEquals(1, context.stateMachines.size, "$context state machines")
        }
    }

    @Test
    fun `examples copy to a real directory`(@TempDir target: Path) {
        Examples.copyTo(target)

        Examples.files().forEach { file ->
            assertEquals(file.text, target.resolve(file.path).readText(), "copy of $file")
        }
    }

    @Test
    fun `descriptor examples load and copy with the files they name`(@TempDir target: Path) {
        DescriptorExamples.names().forEach { name -> assertTrue(DescriptorExamples.text(name).isNotBlank(), name) }

        val descriptor = DescriptorExamples.copyExplicitContext(target)

        assertEquals(DescriptorExamples.text(DescriptorExamples.VALID), descriptor.readText())
        val named = Regex(""""(\w+\.k\w+)"""").findAll(descriptor.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("cleaning_robot.kmeta", "EVENTS.kmeta", "cleaning_robot.kmodel", "cleaning_robot.kstates"), named)
        named.forEach { assertTrue(Files.isRegularFile(target.resolve(it)), "$it was not copied") }
        assertTrue("\"${DescriptorExamples.ATTACHED_TO}\"" in Examples.file("${DescriptorExamples.CONTEXT_DIRECTORY}/cleaning_robot.kmodel").text)
        assertTrue("missing.kmodel" in DescriptorExamples.text(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE))
    }

    @Test
    fun `a synthetic KStates document with 5000 states has at least 5000 lines`() {
        val text = SyntheticKstates(5000)

        assertTrue(SyntheticKstates.lineCount(text) >= 5000, "only ${SyntheticKstates.lineCount(text)} lines")
        assertEquals(1, Regex("INITIAL STATE").findAll(text).count())
        assertEquals(5000, Regex("""(?m)^\s*(INITIAL )?STATE "s\d+" \{""").findAll(text).count())
        assertEquals(text, SyntheticKstates.generate(5000), "generation is deterministic")
        assertEquals(text.count { it == '{' }, text.count { it == '}' }, "braces are balanced")
    }

    @Test
    fun `synthetic documents can be sized by line count`() {
        val text = SyntheticKstates.withMinLines(10_000)

        val lines = SyntheticKstates.lineCount(text)
        assertTrue(lines in 10_000..10_200, "$lines lines")
    }

    @Test
    fun `mutations are deterministic per seed and change the text`() {
        val source = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text

        val first = Mutations.random(source, seed = 42, count = 200)
        val second = Mutations.random(source, seed = 42, count = 200)
        val other = Mutations.random(source, seed = 43, count = 200)

        assertEquals(200, first.size)
        assertEquals(first, second)
        assertNotEquals(first.map { it.text }, other.map { it.text })
        assertTrue(first.count { it.text != source } >= 190, "most mutations change the text")
        assertTrue(first.all { it.operations.size in 1..3 })
        assertEquals(5, Mutations.random("", seed = 1, count = 5).size, "the empty text can be mutated")
    }

    @Test
    fun `prefixes cover every length up to the full text`() {
        assertEquals(listOf("", "a", "ab", "abc"), Mutations.prefixes("abc").toList())
        assertEquals(listOf("", "ab", "abc"), Mutations.prefixes("abc", step = 2).toList())
    }
}
