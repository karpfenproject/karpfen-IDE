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
package io.karpfen.ide.app

import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
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
class IdeRunStateTest {

    /** A run state over a fake session on the test's virtual time. [scope] is the application scope. */
    private class Run(test: TestScope, prepareTime: Duration = Duration.ZERO, exitAfter: Duration? = null) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val services = RecordingServicesFactory(HostPlatform.LINUX, StandardTestDispatcher(test.testScheduler))
        val state = FakeWorkbenchState(scope, services(RingOutputBuffer()))
        val languageToolsLog = RecordingLanguageToolsLog()
        val session = IdeSession(state, CommandRegistry(HostPlatform.LINUX), languageToolsLog.attach(RingOutputBuffer()))
        val running = mutableListOf<IdeSession>()

        /** When set, the preparation fails with it. */
        var failure: Throwable? = null
        val runState = IdeRunState(
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

        run.runState.start()
        runCurrent()
        assertEquals(IdePhase.Starting, run.runState.phase.value)
        advanceTimeBy(2.seconds + 1.milliseconds)

        assertSame(run.session, assertIs<IdePhase.Running>(run.runState.phase.value).session)
        assertEquals(listOf(run.session), run.running, "the error dialogs are attached once")
        assertNull(run.runState.startupFailure)
        run.runState.dispose()
    }

    @Test
    fun `a close request runs the unsaved-changes flow, shuts the runtime down and exits when it allows`() = runTest {
        val run = Run(this)
        run.runState.start()
        runCurrent()

        run.runState.requestClose()
        runCurrent()

        assertEquals(IdePhase.Exited, run.runState.phase.value)
        assertEquals(listOf("requestExit", "shutdownRuntime", "close"), run.state.calls)
        assertFalse(run.runState.stoppingRuntime.value, "a quick shutdown shows no note")
    }

    @Test
    fun `a shutdown of the runtime that takes longer than a second shows the note until the run exits`() = runTest {
        val run = Run(this)
        run.state.shutdownTime = 3.seconds
        run.runState.start()
        runCurrent()

        run.runState.requestClose()
        advanceTimeBy(IdeRunState.STOPPING_NOTE_DELAY - 1.milliseconds)
        assertFalse(run.runState.stoppingRuntime.value)
        advanceTimeBy(2.milliseconds)
        assertTrue(run.runState.stoppingRuntime.value)
        assertIs<IdePhase.Running>(run.runState.phase.value)
        advanceTimeBy(2.seconds)

        assertEquals(IdePhase.Exited, run.runState.phase.value)
        assertFalse(run.runState.stoppingRuntime.value)
        assertEquals(listOf("requestExit", "shutdownRuntime", "close"), run.state.calls)
    }

    @Test
    fun `a cancelled exit keeps the run going and a later close request asks again`() = runTest {
        val run = Run(this)
        run.state.exitAnswer = CompletableDeferred(false)
        run.runState.start()
        runCurrent()

        run.runState.requestClose()
        runCurrent()
        assertIs<IdePhase.Running>(run.runState.phase.value)
        run.state.exitAnswer = CompletableDeferred(true)
        run.runState.requestClose()
        runCurrent()

        assertEquals(IdePhase.Exited, run.runState.phase.value)
        assertEquals(listOf("requestExit", "requestExit", "shutdownRuntime", "close"), run.state.calls)
    }

    @Test
    fun `a second close request while the dialog is open joins the running flow`() = runTest {
        val run = Run(this)
        val answer = CompletableDeferred<Boolean>()
        run.state.exitAnswer = answer
        run.runState.start()
        runCurrent()

        val first = run.runState.requestClose()
        runCurrent()
        val second = run.runState.requestClose()
        runCurrent()
        answer.complete(true)
        runCurrent()

        assertSame(first, second)
        assertEquals(listOf("requestExit", "shutdownRuntime", "close"), run.state.calls)
    }

    @Test
    fun `a close request before the session runs does nothing`() = runTest {
        val run = Run(this, prepareTime = 5.seconds)
        run.runState.start()
        runCurrent()

        val job: Job = run.runState.requestClose()

        assertTrue(job.isCompleted)
        assertEquals(IdePhase.Starting, run.runState.phase.value)
        assertEquals(emptyList(), run.state.calls)
        run.runState.dispose()
    }

    @Test
    fun `the smoke-test timer exits without the unsaved-changes flow and closes the session once`() = runTest {
        val run = Run(this, exitAfter = 6.seconds)
        run.runState.start()

        advanceTimeBy(6.seconds - 1.milliseconds)
        assertIs<IdePhase.Running>(run.runState.phase.value)
        advanceTimeBy(2.milliseconds)

        assertEquals(IdePhase.Exited, run.runState.phase.value)
        assertEquals(listOf("close"), run.state.calls)
        run.runState.dispose()
        assertEquals(listOf("close"), run.state.calls, "dispose does not close twice")
        assertEquals(1, run.languageToolsLog.closed, "the session ends its routing of what dsl-tools logs once")
    }

    @Test
    fun `a session prepared after the run exited is closed and never shown`() = runTest {
        val run = Run(this, prepareTime = 10.seconds, exitAfter = 1.seconds)
        run.runState.start()

        advanceTimeBy(11.seconds)

        assertEquals(IdePhase.Exited, run.runState.phase.value)
        assertEquals(emptyList(), run.running)
        assertEquals(listOf("close"), run.state.calls)
        assertEquals(1, run.languageToolsLog.closed)
    }

    @Test
    fun `a failing preparation ends the run and records the failure`() = runTest {
        val run = Run(this)
        val failure = IllegalStateException("no settings")
        run.failure = failure

        run.runState.start()
        runCurrent()

        assertEquals(IdePhase.Exited, run.runState.phase.value)
        assertSame(failure, run.runState.startupFailure)
        assertEquals(emptyList(), run.running)
    }

    @Test
    fun `dispose ends the run, closes the state and cancels the application scope`() = runTest {
        val run = Run(this)
        run.runState.start()
        runCurrent()

        run.runState.dispose()

        assertEquals(IdePhase.Exited, run.runState.phase.value)
        assertEquals(listOf("close"), run.state.calls)
        assertFalse(run.scope.isActive)
    }
}
