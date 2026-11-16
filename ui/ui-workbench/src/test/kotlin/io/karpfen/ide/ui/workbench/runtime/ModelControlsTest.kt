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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [ModelControls]: Pull and the observation switch are offered only while the session can still ask the
 * runtime, and never once the run ended and its environment was dropped.
 */
class ModelControlsTest {

    private val key = EnvironmentKey("env-1")

    @Test
    fun `pull and the switch follow the states in which the session asks the runtime`() {
        val states = listOf(
            SessionState.Deploying, SessionState.Ready(key), SessionState.Running(key), SessionState.Halted(key),
            SessionState.Stopping(key), SessionState.Stopped(key), SessionState.Failed(DeployProblem(Severity.ERROR, DeployProblemCodes.ENVIRONMENT_LOST, "lost", null), null), SessionState.Closed,
        )

        assertEquals(listOf(false, true, true, true, false, false, false, false), states.map(ModelControls::pulls))
        assertEquals(
            listOf(false, true, true, true, false, false, false, false),
            states.map { ModelControls.switches(it, ObservationMode.ON) },
        )
        assertEquals(
            listOf(false, true, true, false, false, false, false, false),
            states.map { ModelControls.switches(it, ObservationMode.OFF) },
        )
    }
}
