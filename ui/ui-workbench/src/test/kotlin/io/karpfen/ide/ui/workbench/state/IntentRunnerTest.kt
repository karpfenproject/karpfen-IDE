package io.karpfen.ide.ui.workbench.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [IntentRunner]: one flow at a time in call order, jobs, failures reported without cancelling the scope. */
@OptIn(ExperimentalCoroutinesApi::class)
class IntentRunnerTest {

    @Test
    fun `flows run one at a time in call order and exclusive blocks wait for them`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val runner = IntentRunner(scope) { _, _ -> }
        val log = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()

        val first = runner.launch("first") {
            log.add("first start")
            gate.await()
            log.add("first end")
        }
        val second = runner.launch("second") { log.add("second") }
        val exclusive = async { runner.exclusive { log.add("exclusive"); 42 } }
        advanceUntilIdle()
        assertEquals(listOf("first start"), log)
        assertFalse(second.isCompleted)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first start", "first end", "second", "exclusive"), log)
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(42, exclusive.await())
        scope.cancel()
    }

    @Test
    fun `a failing flow is reported and the next flow still runs`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val failures = mutableListOf<Pair<String, String?>>()
        val runner = IntentRunner(scope) { intent, error -> failures.add(intent to error.message) }

        runner.launch("broken") { error("boom") }
        var ran = false
        runner.launch("next") { ran = true }
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, String?>>("broken" to "boom"), failures.toList())
        assertTrue(ran)
        scope.cancel()
    }

    @Test
    fun `editor commands run without focus only for find and go-to`() {
        val unfocused = EditorCommandState(setOf("editor.undo", "editor.find"), focused = false)
        assertFalse(unfocused.allows("editor.undo"))
        assertTrue(unfocused.allows("editor.find"))
        assertFalse(unfocused.allows("editor.goToLine"), "not enabled")
        assertTrue(unfocused.copy(focused = true).allows("editor.undo"))
        assertFalse(EditorCommandState.NONE.allows("editor.find"))
    }
}
