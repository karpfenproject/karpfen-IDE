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
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes

/** What the end of a session's connection means for the session, once its deployment ended. Immutable. */
internal sealed interface EndingVerdict {
    /**
     * The session fails with [problem] and stops its environment.
     *
     * @property problem why.
     */
    data class FailsAndStops(val problem: DeployProblem) : EndingVerdict

    /** The environment was stopped by somebody else: the session reads the final model and is stopped. */
    data object StoppedElsewhere : EndingVerdict

    /**
     * The session has lost its environment for good.
     *
     * @property problem why.
     */
    data class Loses(val problem: DeployProblem) : EndingVerdict

    /**
     * The session keeps its state with the warning [problem].
     *
     * @property problem the warning.
     */
    data class Warns(val problem: DeployProblem) : EndingVerdict

    /** Nothing: the session ended the connection itself. */
    data object Ignored : EndingVerdict
}

/**
 * The session's reaction to the end of its connection after the deployment, unless the session stops or stopped the
 * environment itself. Stateless.
 *
 * | End | Session |
 * |---|---|
 * | handshake rejected | failed with `handshake-rejected`; the environment is stopped |
 * | environment stopped | stopped, from the final model it reads |
 * | environment lost | failed with `environment-lost` |
 * | gave up reconnecting | unchanged, with the warning `connection-lost`; no push arrives any more, so an observation that is on stays synchronizing, and pulls still read the model |
 * | disconnected | unchanged: the session disconnected itself |
 */
internal object ConnectionEndings {
    /** What [end] of the connection to [environment] means for the session. */
    fun of(end: ConnectionEnd, environment: EnvironmentKey): EndingVerdict =
        when (end) {
            is ConnectionEnd.HandshakeRejected -> EndingVerdict.FailsAndStops(
                DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.HANDSHAKE_REJECTED,
                    "The runtime rejected the WebSocket handshake of the session, so it receives nothing more from " +
                        "$environment; the environment is stopped.",
                    null,
                ),
            )
            ConnectionEnd.EnvironmentStopped -> EndingVerdict.StoppedElsewhere
            is ConnectionEnd.EnvironmentLost -> EndingVerdict.Loses(PhaseStates.lost(environment, end.reason))
            is ConnectionEnd.GaveUp -> EndingVerdict.Warns(
                DeployProblem(
                    Severity.WARNING,
                    DeployProblemCodes.CONNECTION_LOST,
                    "The connection to $environment gave up reconnecting after ${end.attempts} attempts (${end.lastFailure}); " +
                        "the model is no longer observed and events cannot be injected.",
                    null,
                ),
            )
            ConnectionEnd.Disconnected -> EndingVerdict.Ignored
        }
}
