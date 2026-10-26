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
package io.karpfen.ide.app.crash

import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uncaught errors in the workbench's error dialog, one at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorDialogPresenterTest {

    private val logFile: Path = Path.of("/home/karl/.karpfen-ide/logs/ide.log")
    private val asked = mutableListOf<DialogRequest.Error>()

    @Test
    fun `the dialog names the error and the log`() {
        val request = errorDialogFor(IllegalStateException("index out of range"), logFile)

        assertEquals(ERROR_DIALOG_TITLE, request.title)
        assertEquals("IllegalStateException: index out of range\n\nThe details were written to $logFile.", request.message)
        assertEquals("java.lang.OutOfMemoryError", errorDialogFor(OutOfMemoryError(), logFile).message.lines().first())
        assertTrue(DialogRequest.Error("t", "m").accepts(DialogResponse.Confirm))
    }

    @Test
    fun `before the workbench runs nothing is shown`() = runTest {
        val presenter = ErrorDialogPresenter(backgroundScope, logFile)

        assertFalse(presenter.show(IllegalStateException("early")))
        runCurrent()

        assertEquals(emptyList(), asked)
    }

    @Test
    fun `once attached an error is shown through the dialog port`() = runTest {
        val presenter = ErrorDialogPresenter(backgroundScope, logFile)
        presenter.attach { asked += it }

        assertTrue(presenter.show(IllegalStateException("late")))
        runCurrent()

        assertEquals(listOf(errorDialogFor(IllegalStateException("late"), logFile)), asked)
    }

    @Test
    fun `while a dialog is open further errors are not queued, afterwards they are shown again`() = runTest {
        val presenter = ErrorDialogPresenter(backgroundScope, logFile)
        val answered = CompletableDeferred<Unit>()
        presenter.attach { request ->
            asked += request
            answered.await()
        }

        assertTrue(presenter.show(IllegalStateException("first")))
        runCurrent()
        val burst = (1..5).map { presenter.show(IllegalStateException("frame $it")) }
        answered.complete(Unit)
        runCurrent()
        val next = presenter.show(IllegalStateException("next"))
        runCurrent()

        assertEquals(List(5) { false }, burst)
        assertTrue(next)
        assertEquals(listOf("first", "next"), asked.map { it.message.substringAfter(": ").substringBefore("\n") })
    }

    @Test
    fun `a failing dialog is logged and does not block later errors`() = runTest {
        val presenter = ErrorDialogPresenter(backgroundScope, logFile)
        var calls = 0
        presenter.attach {
            calls++
            throw IllegalStateException("dialog state broken")
        }

        presenter.show(IllegalStateException("one"))
        runCurrent()
        presenter.show(IllegalStateException("two"))
        runCurrent()

        assertEquals(2, calls)
    }

    @Test
    fun `errors may come from any thread`() = runTest {
        val presenter = ErrorDialogPresenter(backgroundScope, logFile)
        presenter.attach { asked += it }

        val shown = withContext(Dispatchers.Default) { presenter.show(IllegalStateException("worker")) }
        runCurrent()

        assertTrue(shown)
        assertEquals(1, asked.size)
    }
}
