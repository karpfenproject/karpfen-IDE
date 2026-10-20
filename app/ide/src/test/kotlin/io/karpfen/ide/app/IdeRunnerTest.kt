package io.karpfen.ide.app

import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The application run's lifecycle: preparation, close requests, the smoke-test exit timer and disposal. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdeRunnerTest {

    /** A runner over a fake session on the test's virtual time; [scope] is the application scope. */
    private class Run(test: TestScope, prepareTime: Duration = Duration.ZERO, exitAfter: Duration? = null) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val state = FakeWorkbenchState(scope, RecordingServicesFactory(HostPlatform.LINUX, StandardTestDispatcher(test.testScheduler))())
        val session = IdeSession(state, CommandRegistry(HostPlatform.LINUX))
        val running = mutableListOf<IdeSession>()
        var failure: Throwable? = null
        val runner = IdeRunner(
            scope = scope,
            prepare = {
                delay(prepareTime)
                failure?.let { throw it }
                session
            },
            onRunning = { running += it },
            exitAfter = exitAfter,
        )
    }

    @Test
    fun `the run starts without a window and shows the session once it is prepared`() = runTest {
        val run = Run(this, prepareTime = 2.seconds)

        run.runner.start()
        runCurrent()
        assertEquals(IdePhase.Starting, run.runner.phase.value)
        advanceTimeBy(2.seconds + 1.milliseconds)

        assertSame(run.session, assertIs<IdePhase.Running>(run.runner.phase.value).session)
        assertEquals(listOf(run.session), run.running, "the error dialogs are attached once")
        assertNull(run.runner.startupFailure)
        run.runner.dispose()
    }

    @Test
    fun `a close request runs the unsaved-changes flow and exits when it allows`() = runTest {
        val run = Run(this)
        run.runner.start()
        runCurrent()

        run.runner.requestClose()
        runCurrent()

        assertEquals(IdePhase.Exited, run.runner.phase.value)
        assertEquals(listOf("requestExit", "close"), run.state.calls)
    }

    @Test
    fun `a cancelled exit keeps the run going and a later close request asks again`() = runTest {
        val run = Run(this)
        run.state.exitAnswer = CompletableDeferred(false)
        run.runner.start()
        runCurrent()

        run.runner.requestClose()
        runCurrent()
        assertIs<IdePhase.Running>(run.runner.phase.value)
        run.state.exitAnswer = CompletableDeferred(true)
        run.runner.requestClose()
        runCurrent()

        assertEquals(IdePhase.Exited, run.runner.phase.value)
        assertEquals(listOf("requestExit", "requestExit", "close"), run.state.calls)
    }

    @Test
    fun `a second close request while the dialog is open joins the running flow`() = runTest {
        val run = Run(this)
        val answer = CompletableDeferred<Boolean>()
        run.state.exitAnswer = answer
        run.runner.start()
        runCurrent()

        val first = run.runner.requestClose()
        runCurrent()
        val second = run.runner.requestClose()
        runCurrent()
        answer.complete(true)
        runCurrent()

        assertSame(first, second)
        assertEquals(listOf("requestExit", "close"), run.state.calls)
    }

    @Test
    fun `a close request before the session runs does nothing`() = runTest {
        val run = Run(this, prepareTime = 5.seconds)
        run.runner.start()
        runCurrent()

        val job: Job = run.runner.requestClose()

        assertTrue(job.isCompleted)
        assertEquals(IdePhase.Starting, run.runner.phase.value)
        assertEquals(emptyList(), run.state.calls)
        run.runner.dispose()
    }

    @Test
    fun `the smoke-test timer exits without the unsaved-changes flow and closes the state once`() = runTest {
        val run = Run(this, exitAfter = 6.seconds)
        run.runner.start()

        advanceTimeBy(6.seconds - 1.milliseconds)
        assertIs<IdePhase.Running>(run.runner.phase.value)
        advanceTimeBy(2.milliseconds)

        assertEquals(IdePhase.Exited, run.runner.phase.value)
        assertEquals(listOf("close"), run.state.calls)
        run.runner.dispose()
        assertEquals(listOf("close"), run.state.calls, "dispose does not close twice")
    }

    @Test
    fun `a session prepared after the run exited is closed and never shown`() = runTest {
        val run = Run(this, prepareTime = 10.seconds, exitAfter = 1.seconds)
        run.runner.start()

        advanceTimeBy(11.seconds)

        assertEquals(IdePhase.Exited, run.runner.phase.value)
        assertEquals(emptyList(), run.running)
        assertEquals(listOf("close"), run.state.calls)
    }

    @Test
    fun `a failing preparation ends the run and records the failure`() = runTest {
        val run = Run(this)
        val failure = IllegalStateException("no settings")
        run.failure = failure

        run.runner.start()
        runCurrent()

        assertEquals(IdePhase.Exited, run.runner.phase.value)
        assertSame(failure, run.runner.startupFailure)
        assertEquals(emptyList(), run.running)
    }

    @Test
    fun `dispose ends the run, closes the state and cancels the application scope`() = runTest {
        val run = Run(this)
        run.runner.start()
        runCurrent()

        run.runner.dispose()

        assertEquals(IdePhase.Exited, run.runner.phase.value)
        assertEquals(listOf("close"), run.state.calls)
        assertFalse(run.scope.isActive)
    }
}
