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
 * Tests that [AwtFolderChooser] bridges the modal dialog on the AWT event dispatch thread to the suspended caller. It
 * resumes the caller with the answer. Whatever the dialog throws, it resumes the caller with an `IOException`. It never
 * leaves the intent waiting forever.
 */
class AwtFolderChooserTest {

    private val folder: Path = Path.of("/ws/robot").toAbsolutePath()

    /**
     * True if [failure] is [original] or was caused by it. Coroutines may recover an exception as a copy with the
     * original as its cause.
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
