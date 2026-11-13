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
package io.karpfen.ide.ui.workbench.validation

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.core.workspace.validation.ValidationFinding
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.testing.FakeWorkspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [ValidationState] over a [FakeWorkspace] whose validation each test completes when it wants. They cover when a
 * validation can start, the busy state, a second request while one runs, the report dialog, the check before a run
 * that blocks only on errors, and the listeners that hear the report of every check before a run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ValidationStateTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()
    private val workspace = FakeWorkspace(root)
    private val opened = MutableStateFlow<Workspace?>(null)
    private val dialogs = DialogState()

    @Test
    fun `without an open folder nothing can be validated and a request does nothing`() = runTest {
        val validation = validationIn(this)

        assertFalse(validation.isAvailable.value)
        assertFalse(validation.canValidate.value)
        assertTrue(validation.validate().isCompleted)
        assertFalse(validation.checkBeforeRun(), "a run without a folder does not go on")
        assertEquals(0, workspace.validations)

        opened.value = workspace
        runCurrent()
        assertTrue(validation.isAvailable.value)
        assertTrue(validation.canValidate.value)
    }

    @Test
    fun `a validation is busy until its report is done, a second request starts none, and the report is shown`() = runTest {
        opened.value = workspace
        val validation = validationIn(this)
        workspace.validation = CompletableDeferred()

        val first = validation.validate()
        runCurrent()
        val second = validation.validate()

        assertTrue(validation.isBusy.value, "busy at once")
        assertFalse(validation.canValidate.value)
        assertTrue(second.isCompleted, "the second request starts nothing")
        assertEquals(1, workspace.validations)
        assertNull(dialogs.current.value)

        workspace.validation.complete(REPORT)
        runCurrent()

        assertFalse(validation.isBusy.value)
        assertTrue(validation.canValidate.value)
        val shown = assertIs<DialogRequest.ValidationReport>(dialogs.current.value)
        assertEquals(ValidationState.TITLE, shown.title)
        assertEquals("Validate Project", shown.title)
        assertEquals(REPORT, shown.validation)
        dialogs.respond(DialogResponse.Confirm)
        runCurrent()
        assertTrue(first.isCompleted)
        assertNull(dialogs.current.value)
    }

    @Test
    fun `a clean project shows the report too`() = runTest {
        opened.value = workspace
        val validation = validationIn(this)

        validation.validate()
        runCurrent()

        val shown = assertIs<DialogRequest.ValidationReport>(dialogs.current.value)
        assertEquals(emptyList(), shown.validation.findings)
    }

    @Test
    fun `a run goes on without a dialog when the project has warnings only`() = runTest {
        opened.value = workspace
        val validation = validationIn(this)
        workspace.validation = CompletableDeferred(ProjectValidation(root, listOf(WARNING)))

        assertTrue(validation.checkBeforeRun())
        assertNull(dialogs.current.value)
        assertEquals(1, workspace.validations)
    }

    @Test
    fun `a run with an error shows the report under the blocked title and does not go on`() = runTest {
        opened.value = workspace
        val validation = validationIn(this)
        workspace.validation = CompletableDeferred(REPORT)

        val check = async { validation.checkBeforeRun() }
        runCurrent()

        val shown = assertIs<DialogRequest.ValidationReport>(dialogs.current.value)
        assertEquals(ValidationState.RUN_BLOCKED_TITLE, shown.title)
        assertFalse(check.isCompleted, "the run waits until the report is closed")
        dialogs.respond(DialogResponse.Confirm)
        assertFalse(check.await())
    }

    @Test
    fun `every check before a run hands its report to the listeners before a dialog, until they are disposed`() = runTest {
        opened.value = workspace
        val validation = validationIn(this)
        val heard = ArrayList<ProjectValidation>()
        val handle = validation.observeRunChecks { report ->
            assertNull(dialogs.current.value, "the listener hears the report before its dialog")
            heard += report
        }
        val warnings = ProjectValidation(root, listOf(WARNING))
        workspace.validation = CompletableDeferred(warnings)
        assertTrue(validation.checkBeforeRun())

        workspace.validation = CompletableDeferred(REPORT)
        val blocked = async { validation.checkBeforeRun() }
        runCurrent()
        dialogs.respond(DialogResponse.Confirm)
        assertFalse(blocked.await())
        validation.validate()
        runCurrent()
        dialogs.respond(DialogResponse.Confirm)
        runCurrent()

        assertEquals(listOf(warnings, REPORT), heard, "the button's validation is no check before a run")

        handle.dispose()
        workspace.validation = CompletableDeferred(warnings)
        assertTrue(validation.checkBeforeRun())
        assertEquals(2, heard.size, "a disposed listener hears nothing")
    }

    @Test
    fun `a run while the button's validation runs waits for it and then validates again`() = runTest {
        opened.value = workspace
        val validation = validationIn(this)
        workspace.validation = CompletableDeferred()

        validation.validate()
        val check = async { validation.checkBeforeRun() }
        runCurrent()
        assertEquals(1, workspace.validations, "the run waits for the running validation")

        workspace.validation.complete(ProjectValidation(root, emptyList()))
        runCurrent()
        dialogs.respond(DialogResponse.Confirm)

        assertTrue(check.await())
        assertEquals(2, workspace.validations)
    }

    @Test
    fun `a workspace that is closed during the validation shows nothing and stops the run`() = runTest {
        opened.value = workspace
        val validation = validationIn(this)
        workspace.validation = CompletableDeferred()

        validation.validate()
        val check = async { validation.checkBeforeRun() }
        runCurrent()
        workspace.validation.completeExceptionally(IllegalStateException("closed"))
        runCurrent()

        assertFalse(check.await())
        assertNull(dialogs.current.value)
        assertFalse(validation.isBusy.value)
    }

    /** A validation state of [scope] over [opened] and [dialogs]. */
    private fun validationIn(scope: TestScope): ValidationState = ValidationState(scope.backgroundScope, opened, dialogs)

    private companion object {
        /** A warning of the check across files. */
        val WARNING = ValidationFinding(Path.of("/ws/a.kstates").toAbsolutePath(), 4, 16, Severity.WARNING, "project.unreachable-state", "lost")

        /** A report with one error and [WARNING]. */
        val REPORT = ProjectValidation(
            Path.of("/ws").toAbsolutePath(),
            listOf(ValidationFinding(Path.of("/ws/a.kmeta").toAbsolutePath(), 2, 1, Severity.ERROR, "syntax", "missing '}'"), WARNING),
        )
    }
}
