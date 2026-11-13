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

import io.karpfen.ide.app.launch
import io.karpfen.ide.app.launch.ExitSequence
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.ui.workbench.dialog.ValidationReportTexts
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * Covers the way into the headless mode: `launch` hands a headless command line to the headless mode, switches AWT to
 * headless and never creates the window's application, and [HeadlessLaunch] answers help and a wrong command line and
 * validates a project on a thread of its own with the IDE's production wiring.
 */
class HeadlessLaunchTest {

    @TempDir
    lateinit var root: Path

    private val standardOutput = ByteArrayOutputStream()
    private val standardError = ByteArrayOutputStream()
    private val console = HeadlessConsole(PrintStream(standardOutput, true, Charsets.UTF_8), PrintStream(standardError, true, Charsets.UTF_8))

    @Test
    fun `a headless command line goes to the headless mode with AWT headless, and the window is never created`() {
        val before = System.getProperty(HeadlessLaunch.AWT_HEADLESS_PROPERTY)
        val commands = ArrayList<HeadlessCommand>()
        try {
            val status = launch(
                arrayOf("validate", root.toString()),
                application = { fail("the window's application is not created") },
                headless = { commands += it; HeadlessExitCodes.ERRORS_FOUND },
            )

            assertEquals(HeadlessExitCodes.ERRORS_FOUND, status)
            assertEquals(listOf<HeadlessCommand>(HeadlessCommand.Validate(root.toAbsolutePath().normalize())), commands)
            assertEquals("true", System.getProperty(HeadlessLaunch.AWT_HEADLESS_PROPERTY))
        } finally {
            if (before == null) System.clearProperty(HeadlessLaunch.AWT_HEADLESS_PROPERTY) else System.setProperty(HeadlessLaunch.AWT_HEADLESS_PROPERTY, before)
        }
    }

    @Test
    fun `help prints the usage on standard output and ends with 0`() {
        assertEquals(HeadlessExitCodes.OK, launchWithoutHook().run(HeadlessCommand.Help))

        assertEquals(HeadlessUsage.TEXT + NEWLINE, standardOutput.toString(Charsets.UTF_8))
        assertEquals("", standardError.toString(Charsets.UTF_8))
    }

    @Test
    fun `a wrong command line prints its message and the usage on standard error and ends with 2`() {
        assertEquals(HeadlessExitCodes.USAGE, launchWithoutHook().run(HeadlessCommand.Invalid("unknown option '--bogus'")))

        assertEquals("", standardOutput.toString(Charsets.UTF_8))
        assertEquals("karpfen-ide: unknown option '--bogus'" + NEWLINE + HeadlessUsage.TEXT + NEWLINE, standardError.toString(Charsets.UTF_8))
    }

    @Test
    fun `validate runs with the production wiring and writes nothing into the project`() {
        SynthesizedModels.copyTo(root, SynthesizedModel.LAMP)

        val status = launchWithoutHook().run(HeadlessCommand.Validate(root.toAbsolutePath().normalize()))

        assertEquals(HeadlessExitCodes.OK, status)
        assertEquals(ValidationReportTexts.NO_PROBLEMS + NEWLINE, standardOutput.toString(Charsets.UTF_8))
        assertFalse(Files.exists(root.resolve(ProjectSettings.FILE_NAME)))
    }

    /** A launch over [console] whose shutdown sequence registers no hook, because validate and help add no step. */
    private fun launchWithoutHook(): HeadlessLaunch = HeadlessLaunch(console, ExitSequence { fail("no shutdown hook is registered") })

    private companion object {
        /** What ends each line the console writes. */
        val NEWLINE: String = System.lineSeparator()
    }
}
