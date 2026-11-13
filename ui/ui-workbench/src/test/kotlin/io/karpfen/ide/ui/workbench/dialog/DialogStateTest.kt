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
package io.karpfen.ide.ui.workbench.dialog

import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [DialogState]. It shows one dialog at a time, accepts only the answers of the request's type, and handles
 * cancellation. The tests also check that [DialogPresentation] offers exactly those answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialogStateTest {

    private val path: Path = Path.of("/ws/robot.kstates").toAbsolutePath()

    @Test
    fun `ask suspends until respond answers and dialogs are shown one at a time`() = runTest {
        val dialogs = DialogState()
        val first = async { dialogs.ask(DialogRequest.Rename(path)) }
        val second = async { dialogs.ask(DialogRequest.Error("t", "m")) }
        runCurrent()
        assertEquals(DialogRequest.Rename(path), dialogs.current.value)

        dialogs.respond(DialogResponse.Text("new.kstates"))
        assertEquals(DialogResponse.Text("new.kstates"), first.await())
        runCurrent()
        assertEquals(DialogRequest.Error("t", "m"), dialogs.current.value)
        dialogs.respond(DialogResponse.Confirm)
        assertEquals(DialogResponse.Confirm, second.await())
        assertNull(dialogs.current.value)
    }

    @Test
    fun `a response the dialog does not accept is rejected and responding without a dialog does nothing`() = runTest {
        val dialogs = DialogState()
        dialogs.respond(DialogResponse.Cancel)
        val answer = async { dialogs.ask(DialogRequest.UnsavedChanges(listOf(path))) }
        runCurrent()
        assertFailsWith<IllegalArgumentException> { dialogs.respond(DialogResponse.Text("x")) }
        assertFailsWith<IllegalArgumentException> { dialogs.respond(DialogResponse.Confirm) }
        dialogs.respond(DialogResponse.Discard)
        assertEquals(DialogResponse.Discard, answer.await())
    }

    @Test
    fun `cancelling the caller removes its dialog`() = runTest {
        val dialogs = DialogState()
        val asking = async { dialogs.ask(DialogRequest.ConfirmDelete(path, toTrash = false)) }
        runCurrent()
        asking.cancel()
        runCurrent()
        assertNull(dialogs.current.value)
        assertTrue(asking.isCancelled)
    }

    @Test
    fun `every presented button answers with a response its request accepts`() {
        val requests = listOf(
            DialogRequest.UnsavedChanges(listOf(path)),
            DialogRequest.UnsavedChanges(listOf(path, path.resolveSibling("b.kmeta"))),
            DialogRequest.ConfirmDelete(path, toTrash = true),
            DialogRequest.ConfirmDelete(path, toTrash = false),
            DialogRequest.ConfirmOverwrite(listOf(path)),
            DialogRequest.ConfirmOverwrite(listOf(path, path.resolveSibling("b.kmeta"))),
            DialogRequest.Rename(path),
            DialogRequest.NewFile(path.parent, FileTemplateKind.ENVIRONMENT),
            DialogRequest.Error("Title", "Message"),
            DialogRequest.ConfigureRun(path.parent, emptyList()),
            DialogRequest.ConfigureRun(path.parent, listOf(path.resolveSibling("a.kbridge.json")), setOf(0), capture = true),
        )
        requests.forEach { request ->
            val presentation = DialogPresentation.of(request)
            assertTrue(request.accepts(presentation.dismiss), "$request dismiss")
            assertEquals(1, presentation.buttons.count { it.isDefault }, "$request has one default button")
            presentation.buttons.forEach { button ->
                val response = presentation.responseFor(button.choice, "name")
                assertTrue(response != null && request.accepts(response), "$request ${button.label}")
            }
        }
    }

    @Test
    fun `presentations name the files and text dialogs need a non-blank name`() {
        val rename = DialogPresentation.of(DialogRequest.Rename(path))
        assertEquals("robot.kstates", rename.input)
        assertNull(rename.responseFor(DialogChoice.SubmitText, "  "))
        assertEquals(DialogResponse.Text("b.kstates"), rename.responseFor(DialogChoice.SubmitText, " b.kstates "))

        val unsaved = DialogPresentation.of(DialogRequest.UnsavedChanges(listOf(path, path.resolveSibling("b.kmeta"))))
        assertTrue(unsaved.message.contains("2 files") && unsaved.message.contains("b.kmeta"))
        assertEquals(listOf("Save", "Don't Save", "Cancel"), unsaved.buttons.map { it.label })

        assertTrue(DialogPresentation.of(DialogRequest.ConfirmDelete(path, toTrash = true)).message.contains("trash"))
        assertFalse(DialogPresentation.of(DialogRequest.ConfirmDelete(path, toTrash = false)).message.contains("trash"))
        assertEquals("New Environment File", DialogPresentation.of(DialogRequest.NewFile(path.parent, FileTemplateKind.ENVIRONMENT)).title)
        assertEquals(listOf("OK"), DialogPresentation.of(DialogRequest.Error("T", "M")).buttons.map { it.label })

        val overwrite = DialogPresentation.of(DialogRequest.ConfirmOverwrite(listOf(path, path.resolveSibling("b.kmeta"))))
        assertTrue(overwrite.message.contains("2 files") && overwrite.message.contains("b.kmeta"))
        assertEquals(listOf("Overwrite", "Cancel"), overwrite.buttons.map { it.label })
        assertEquals(DialogResponse.Cancel, overwrite.buttons.single { it.isDefault }.let { (it.choice as DialogChoice.Answer).response })
        assertEquals(DialogResponse.Cancel, overwrite.dismiss, "Enter and Escape keep the version on disk")
    }

    @Test
    fun `the run dialog accepts ticked bridges of its list with or without the capture, and nothing else`() {
        val root = path.parent
        val bridges = listOf(root.resolve("a.kbridge.json"), root.resolve("lab/b.kbridge.json"))
        val request = DialogRequest.ConfigureRun(root, bridges, preselected = setOf(1), capture = true)

        assertTrue(request.accepts(DialogResponse.RunWith(setOf(0, 1), capture = false)))
        assertTrue(request.accepts(DialogResponse.RunWith(emptySet(), capture = true)))
        assertTrue(request.accepts(DialogResponse.Cancel))
        assertFalse(request.accepts(DialogResponse.RunWith(setOf(2), capture = false)), "there is no third bridge")
        assertFalse(request.accepts(DialogResponse.Chosen(0)))
        assertFailsWith<IllegalArgumentException> { DialogRequest.ConfigureRun(root, bridges, preselected = setOf(2)) }

        val presentation = DialogPresentation.of(request)
        assertEquals("Run", presentation.title)
        assertEquals(listOf("a.kbridge.json", "lab/b.kbridge.json"), presentation.ticks?.map { it.replace('\\', '/') })
        assertEquals(setOf(1), presentation.preticked)
        assertEquals("Capture run to file", presentation.option)
        assertTrue(presentation.optionTicked)
        assertEquals(DialogResponse.RunWith(setOf(1), capture = true), presentation.responseFor(DialogChoice.SubmitRun, ""))
        assertEquals(
            DialogResponse.RunWith(setOf(0), capture = false),
            presentation.responseFor(DialogChoice.SubmitRun, "", ticked = setOf(0), optioned = false),
        )

        val alone = DialogPresentation.of(DialogRequest.ConfigureRun(root, emptyList()))
        assertEquals(emptyList(), alone.ticks)
        assertFalse(alone.optionTicked)
        assertFalse("bridge" in alone.message, alone.message)
        assertEquals(DialogResponse.RunWith(emptySet(), capture = false), alone.responseFor(DialogChoice.SubmitRun, ""))
    }
}
