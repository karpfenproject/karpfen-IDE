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
package io.karpfen.ide.app.headless

import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.ui.workbench.dialog.ValidationReportTexts
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `validate` through [HeadlessValidation] over the IDE's own wiring in a [HeadlessRig]. The project is the
 * synthesized lamp. The tests check the status and the lines of a clean project, of a project with errors and of one
 * with warnings only, the JSON document of a project with errors, and that nothing is written into the project or the
 * user's settings. The example projects of the repository validate without problems.
 */
class HeadlessValidationTest {

    @TempDir
    lateinit var temp: Path

    private val root: Path get() = temp.resolve("project").toAbsolutePath().normalize()

    private val settingsDirectory: Path get() = temp.resolve("settings")

    @Test
    fun `a clean project prints that no problems were found and ends with 0`() = runTest(timeout = TIMEOUT) {
        SynthesizedModels.copyTo(root, SynthesizedModel.LAMP)

        val (status, rig) = validate()

        assertEquals(HeadlessExitCodes.OK, status)
        assertEquals(listOf(ValidationReportTexts.NO_PROBLEMS), rig.output)
        assertEquals(emptyList(), rig.errors)
        assertFalse(Files.exists(root.resolve(ProjectSettings.FILE_NAME)), "the project file is not written")
        assertFalse(Files.exists(settingsDirectory), "the user's settings are not written")
    }

    @Test
    fun `errors are printed first with file, line and severity, then the summary, and end with 1`() = runTest(timeout = TIMEOUT) {
        SynthesizedModels.copyTo(root, SynthesizedModel.LAMP)
        replace("lamp.kstates", "STATEMACHINE", "STATMACHINE")
        addUnreachableState("switch.kstates")

        val (status, rig) = validate()

        assertEquals(HeadlessExitCodes.ERRORS_FOUND, status)
        val (first, second, last) = rig.output
        assertTrue(first.startsWith("lamp.kstates:1: error: "), first)
        assertTrue(second.startsWith("switch.kstates:") && ": warning: " in second, second)
        assertEquals("1 error and 1 warning", last)
        assertEquals(3, rig.output.size)
    }

    @Test
    fun `warnings only are printed with the summary and end with 0`() = runTest(timeout = TIMEOUT) {
        SynthesizedModels.copyTo(root, SynthesizedModel.LAMP)
        addUnreachableState("lamp.kstates")

        val (status, rig) = validate()

        assertEquals(HeadlessExitCodes.OK, status)
        val (finding, summary) = rig.output
        assertTrue(finding.matches(Regex("""lamp\.kstates:\d+: warning: .*lost.*""")), finding)
        assertEquals("1 warning", summary)
    }

    @Test
    fun `the JSON format prints one document of the findings and nothing else, and ends with 1 for errors`() = runTest(timeout = TIMEOUT) {
        SynthesizedModels.copyTo(root, SynthesizedModel.LAMP)
        replace("lamp.kstates", "STATEMACHINE", "STATMACHINE")
        addUnreachableState("switch.kstates")

        val (status, rig) = validate(OutputFormat.JSON)

        assertEquals(HeadlessExitCodes.ERRORS_FOUND, status)
        val document = assertIs<JsonObject>(Json.parseToJsonElement(rig.output.joinToString("\n")))
        assertEquals(JsonPrimitive(root.toString()), document["root"])
        assertEquals(JsonPrimitive(1), document["errorCount"])
        assertEquals(JsonPrimitive(1), document["warningCount"])
        val problems = assertIs<JsonArray>(document["problems"]).map { assertIs<JsonObject>(it) }
        assertEquals(listOf("lamp.kstates", "switch.kstates"), problems.map { (it["path"] as JsonPrimitive).content })
        assertEquals(listOf("error", "warning"), problems.map { (it["severity"] as JsonPrimitive).content })
        assertEquals(emptyList(), rig.errors)
    }

    @Test
    fun `the example projects of the repository validate without problems, with the language features of their settings`() =
        runTest(timeout = TIMEOUT) {
            for (example in listOf("lamp", "spares")) {
                copyExample(example)

                val (status, rig) = validate()

                assertEquals(HeadlessExitCodes.OK to listOf(ValidationReportTexts.NO_PROBLEMS), status to rig.output, example)
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `without its settings the attachment example reports its constructs as switched off`() = runTest(timeout = TIMEOUT) {
        copyExample("spares")
        Files.delete(root.resolve(ProjectSettings.FILE_NAME))

        val (status, rig) = validate()

        assertEquals(HeadlessExitCodes.ERRORS_FOUND, status)
        val files = rig.output.dropLast(1).map { it.substringBefore(':') }.toSet()
        assertTrue(files.containsAll(listOf("spares.kmeta", "glowing.kstates", "room.kstates")), "${rig.output}")
    }

    @Test
    fun `a behaves after that names no attachable state machine is an error at the metamodel`() = runTest(timeout = TIMEOUT) {
        copyExample("spares")
        replace("spares.kmeta", "behaves after \"glowing\"", "behaves after \"gone\"")

        val (status, rig) = validate()

        assertEquals(HeadlessExitCodes.ERRORS_FOUND, status)
        assertTrue(rig.output.first().startsWith("spares.kmeta:4: error: "), "${rig.output}")
        assertEquals("1 error", rig.output.last())
    }

    /** Copies the example project [name] of the repository, its settings file included, to [root]. */
    private fun copyExample(name: String) {
        val examples = Path.of(checkNotNull(System.getProperty("karpfen.examples")) { "the build names the examples" })
        examples.resolve(name).toFile().copyRecursively(root.toFile())
    }

    /**
     * Validates [root] in a new rig, printing in [format], and returns the status with the rig, whose console holds the
     * lines.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.validate(format: OutputFormat = OutputFormat.TEXT): Pair<Int, HeadlessRig> {
        val rig = HeadlessRig(testScheduler, settingsDirectory)
        try {
            val status = rig.validate(HeadlessCommand.Validate(root, format))
            rig.settle()
            return status.await() to rig
        } finally {
            rig.close()
        }
    }

    /** Replaces the first [old] by [new] in the project file [name]. */
    private fun replace(name: String, old: String, new: String) {
        val file = root.resolve(name)
        Files.writeString(file, Files.readString(file).replaceFirst(old, new))
    }

    /** Adds a state named `lost` that no transition reaches to the state machine in the project file [name]. */
    private fun addUnreachableState(name: String) {
        replace(name, "    STATES {\n", "    STATES {\n        STATE \"lost\" {\n        }\n")
    }

    private companion object {
        /** A bound for a test that hangs. It is no assertion on time. */
        val TIMEOUT = 60.seconds
    }
}
