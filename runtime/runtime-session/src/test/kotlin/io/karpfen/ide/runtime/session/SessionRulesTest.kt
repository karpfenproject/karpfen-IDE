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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.PhaseKind
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers the rules of a session as tables: the session's state for every phase of its environment, the commands every
 * state allows, and the injection availability of every state with and without an open connection.
 */
class SessionRulesTest {

    @Test
    fun `every phase of the environment gives the session's state, with the problem it raises`() {
        assertEquals(PhaseVerdict.Keeps(null), PhaseStates.of(EnvironmentPhase.Configuring(Uploads.NONE), KEY))
        assertEquals(PhaseVerdict.Becomes(SessionState.Ready(KEY), null), PhaseStates.of(EnvironmentPhase.Activated, KEY))
        assertEquals(PhaseVerdict.Becomes(SessionState.Running(KEY), null), PhaseStates.of(EnvironmentPhase.Running, KEY))
        val halted = assertIs<PhaseVerdict.Becomes>(PhaseStates.of(EnvironmentPhase.Halted, KEY))
        assertEquals(SessionState.Halted(KEY), halted.state)
        assertEquals(DeployProblemCodes.ENGINE_HALTED to Severity.ERROR, halted.problem?.let { it.code to it.severity })
        assertEquals(PhaseVerdict.Becomes(SessionState.Stopped(KEY), null), PhaseStates.of(EnvironmentPhase.Stopped, KEY))
        val uncertain = assertIs<PhaseVerdict.Keeps>(
            PhaseStates.of(EnvironmentPhase.Uncertain(setOf(PhaseKind.ACTIVATED, PhaseKind.RUNNING), "the start got no answer.", Uploads.NONE), KEY),
        )
        assertEquals(DeployProblemCodes.RUNTIME_NO_ANSWER, uncertain.problem?.code)
        val lost = assertIs<PhaseVerdict.Loses>(PhaseStates.of(EnvironmentPhase.Lost("the runtime lists no such environment"), KEY))
        assertEquals(DeployProblemCodes.ENVIRONMENT_LOST to Severity.ERROR, lost.problem.code to lost.problem.severity)
    }

    @Test
    fun `every state allows exactly the commands of the table`() {
        val allowed = STATES.associateWith { state -> COMMANDS.filter { CommandRules.refusal(it, state) == null } }

        assertEquals(
            mapOf(
                SessionState.Deploying to emptyList(),
                SessionState.Ready(KEY) to COMMANDS,
                SessionState.Running(KEY) to COMMANDS - SessionCommand.Start,
                SessionState.Halted(KEY) to listOf(SessionCommand.Stop, SessionCommand.SetObservation(on = false), SessionCommand.Pull),
                SessionState.Stopping(KEY) to emptyList(),
                SessionState.Stopped(KEY) to listOf(SessionCommand.Pull),
                FAILED to emptyList(),
                SessionState.Closed to emptyList(),
            ),
            allowed,
        )
        assertEquals(
            listOf(SessionState.Ready(KEY), SessionState.Running(KEY)),
            STATES.filter { CommandRules.injectionRefusal(it) == null },
        )
        assertEquals("Starting the environment is not possible while the environment runs.", CommandRules.refusal(SessionCommand.Start, SessionState.Running(KEY)))
    }

    @Test
    fun `events are not yet injectable while deploying, queued while ready, live while running and else unavailable`() {
        assertEquals(InjectionAvailability.NotYet, InjectionAvailabilities.of(SessionState.Deploying, linkOpen = false))
        assertEquals(InjectionAvailability.Queued, InjectionAvailabilities.of(SessionState.Ready(KEY), linkOpen = true))
        assertEquals(InjectionAvailability.Live, InjectionAvailabilities.of(SessionState.Running(KEY), linkOpen = true))
        assertIs<InjectionAvailability.Unavailable>(InjectionAvailabilities.of(SessionState.Ready(KEY), linkOpen = false))
        assertIs<InjectionAvailability.Unavailable>(InjectionAvailabilities.of(SessionState.Running(KEY), linkOpen = false))
        (STATES - SessionState.Deploying - SessionState.Ready(KEY) - SessionState.Running(KEY)).forEach { state ->
            assertIs<InjectionAvailability.Unavailable>(InjectionAvailabilities.of(state, linkOpen = true), "$state")
        }
        assertNull(CommandRules.injectionRefusal(SessionState.Running(KEY)))
    }

    private companion object {
        /** The environment of the states. */
        val KEY = EnvironmentKey("env-1761560123456")

        /** A failed state. */
        val FAILED = SessionState.Failed(DeployProblem(Severity.ERROR, DeployProblemCodes.RUNTIME_ERROR, "Unexpected error", null), null)

        /** One state of every kind. */
        val STATES = listOf(
            SessionState.Deploying, SessionState.Ready(KEY), SessionState.Running(KEY), SessionState.Halted(KEY),
            SessionState.Stopping(KEY), SessionState.Stopped(KEY), FAILED, SessionState.Closed,
        )

        /** One command of every kind. */
        val COMMANDS = listOf(
            SessionCommand.Start, SessionCommand.Stop, SessionCommand.ForceState("lamp", "on"), SessionCommand.SetObservation(on = true),
            SessionCommand.SetObservation(on = false), SessionCommand.Pull,
        )
    }
}
