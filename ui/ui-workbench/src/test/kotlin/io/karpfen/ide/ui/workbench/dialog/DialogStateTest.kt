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

/** [DialogState]: one dialog at a time, typed answers, cancellation; [DialogPresentation] offers exactly those answers. */
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
}
