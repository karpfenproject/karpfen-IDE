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
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes

/** What a phase of the environment means for the session's state. Immutable. */
internal sealed interface PhaseVerdict {
    /**
     * The session takes [state].
     *
     * @property state the state.
     * @property problem the problem the phase raises, or null.
     */
    data class Becomes(val state: SessionState, val problem: DeployProblem?) : PhaseVerdict

    /**
     * The session keeps its state.
     *
     * @property problem the problem the phase raises, or null.
     */
    data class Keeps(val problem: DeployProblem?) : PhaseVerdict

    /**
     * The session has lost its environment for good.
     *
     * @property problem why.
     */
    data class Loses(val problem: DeployProblem) : PhaseVerdict
}

/**
 * Maps the environment's phase to the session's state once the deployment ended. Stateless.
 *
 * | Phase | Session |
 * |---|---|
 * | configuring | unchanged |
 * | activated | ready |
 * | running | running |
 * | halted, as a snapshot revealed | halted, with the problem `engine-halted` |
 * | stopped | stopped |
 * | uncertain | unchanged, with the problem `runtime-no-answer` |
 * | lost | failed, with the problem `environment-lost` |
 *
 * The configuring phase only occurs while the deployment runs, and the session is deploying then. An uncertain phase
 * is resolved by the next pull or command.
 */
internal object PhaseStates {
    /** What [phase] of the environment [environment] means for the session. */
    fun of(phase: EnvironmentPhase, environment: EnvironmentKey): PhaseVerdict =
        when (phase) {
            is EnvironmentPhase.Configuring -> PhaseVerdict.Keeps(null)
            EnvironmentPhase.Activated -> PhaseVerdict.Becomes(SessionState.Ready(environment), null)
            EnvironmentPhase.Running -> PhaseVerdict.Becomes(SessionState.Running(environment), null)
            EnvironmentPhase.Halted -> PhaseVerdict.Becomes(SessionState.Halted(environment), halted(environment))
            EnvironmentPhase.Stopped -> PhaseVerdict.Becomes(SessionState.Stopped(environment), null)
            is EnvironmentPhase.Uncertain -> PhaseVerdict.Keeps(
                DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.RUNTIME_NO_ANSWER,
                    "The runtime did not answer, so the phase of $environment is uncertain: ${phase.reason} The next pull or " +
                        "command settles it.",
                    null,
                ),
            )
            is EnvironmentPhase.Lost -> PhaseVerdict.Loses(lost(environment, phase.reason))
        }

    /** The problem that the engine of [environment] ended on its own. */
    fun halted(environment: EnvironmentKey): DeployProblem =
        DeployProblem(
            Severity.ERROR,
            DeployProblemCodes.ENGINE_HALTED,
            "The engine of $environment ended on its own; the runtime's console log has the reason.",
            null,
        )

    /** The problem that the runtime no longer knows [environment], concluded because [reason]. */
    fun lost(environment: EnvironmentKey, reason: String): DeployProblem =
        DeployProblem(
            Severity.ERROR,
            DeployProblemCodes.ENVIRONMENT_LOST,
            "The runtime no longer knows the environment $environment, probably because it restarted: $reason",
            null,
        )
}
