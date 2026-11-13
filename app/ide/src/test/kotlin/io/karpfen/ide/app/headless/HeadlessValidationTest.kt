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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `validate` through [HeadlessValidation] over the IDE's own wiring in a [HeadlessRig]. The project is the
 * synthesized lamp. The tests check the status and the lines of a clean project, of a project with errors and of one
 * with warnings only, and that nothing is written into the project or the user's settings.
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

    /** Validates [root] in a new rig and returns the status with the rig, whose console holds the lines. */
    private suspend fun kotlinx.coroutines.test.TestScope.validate(): Pair<Int, HeadlessRig> {
        val rig = HeadlessRig(testScheduler, settingsDirectory)
        try {
            val status = rig.validate(HeadlessCommand.Validate(root))
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
