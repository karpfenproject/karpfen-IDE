package io.karpfen.ide.ui.workbench.files.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.EventQueue
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [AwtFolderChooser] bridges the modal dialog on the AWT event dispatch thread to the suspended caller: it resumes it
 * with the answer or, whatever the dialog throws, with an `IOException`, never leaving the intent waiting forever.
 */
class AwtFolderChooserTest {

    private val folder: Path = Path.of("/ws/robot").toAbsolutePath()

    /**
     * True if [failure] is [original] or caused by it: coroutines may recover an exception as a copy with the original
     * as its cause.
     */
    private fun causedBy(failure: Throwable, original: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { it === original }

    @Test
    fun `the dialog runs on the event dispatch thread and its answer is returned`() = runBlocking {
        var onEdt = false
        var shown: Pair<String, Path?>? = null
        val chooser = AwtFolderChooser(
            dialog = { title, initial ->
                onEdt = EventQueue.isDispatchThread()
                shown = title to initial
                folder
            },
            headless = { false },
        )

        assertEquals(folder, withTimeout(10.seconds) { chooser.chooseFolder("Open Folder", folder.parent) })
        assertTrue(onEdt)
        assertEquals("Open Folder" to folder.parent, shown)
    }

    @Test
    fun `a dialog that throws fails the call with an IOException instead of never resuming it`() = runBlocking {
        val shellFolderBug = InternalError("Could not get shell folder ID list")
        val chooser = AwtFolderChooser(dialog = { _, _ -> throw shellFolderBug }, headless = { false })

        val failure = withTimeout(10.seconds) { assertFailsWith<IOException> { chooser.chooseFolder("Open Folder", null) } }

        assertTrue(causedBy(failure, shellFolderBug), "$failure")
        assertEquals("The folder dialog failed: Could not get shell folder ID list", failure.message)
    }

    @Test
    fun `headless, no dialog is shown`() = runBlocking {
        val chooser = AwtFolderChooser(dialog = { _, _ -> error("no dialog when headless") }, headless = { true })

        assertNull(chooser.chooseFolder("Open Folder", null))
    }
}
