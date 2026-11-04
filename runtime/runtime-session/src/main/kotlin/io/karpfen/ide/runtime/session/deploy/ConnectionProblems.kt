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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.HandshakeRejection
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlin.time.Duration

/** The problems of a connection that failed a deploy step, all of them errors. Stateless. */
internal object ConnectionProblems {
    /** The problem of a connection that ended with [end]. */
    fun ended(end: ConnectionEnd): DeployProblem =
        when (end) {
            is ConnectionEnd.HandshakeRejected -> error(
                DeployProblemCodes.HANDSHAKE_REJECTED,
                "The runtime rejected the WebSocket handshake: ${reasonOf(end.rejection)}.",
            )
            is ConnectionEnd.GaveUp -> error(
                DeployProblemCodes.CONNECTION_LOST,
                "The connection could not be opened after ${end.attempts} attempts: ${end.lastFailure}",
            )
            is ConnectionEnd.EnvironmentLost -> error(
                DeployProblemCodes.ENVIRONMENT_LOST,
                "The runtime no longer knows the environment: ${end.reason}",
            )
            ConnectionEnd.EnvironmentStopped -> error(
                DeployProblemCodes.CONNECTION_LOST,
                "The connection ended because the environment was stopped.",
            )
            ConnectionEnd.Disconnected -> error(DeployProblemCodes.CONNECTION_LOST, "The connection was closed.")
        }

    /** The problem of a connection that was in [state] instead of open when [wait] passed. */
    fun notOpen(state: ConnectionState, wait: Duration): DeployProblem =
        error(DeployProblemCodes.CONNECTION_LOST, "The connection was not open within $wait (it was ${describe(state)}).")

    /** [rejection] in words, the runtime's reason where it has one. */
    private fun reasonOf(rejection: HandshakeRejection): String =
        when (rejection) {
            HandshakeRejection.FirstMessageNotText -> "First message must be text"
            HandshakeRejection.InvalidAuthFormat -> "Invalid auth format"
            HandshakeRejection.InvalidCredentials -> "Invalid credentials"
            is HandshakeRejection.Other -> "close code ${rejection.code}" + (rejection.reason?.let { ", $it" } ?: "")
        }

    /** [state] in words. */
    private fun describe(state: ConnectionState): String =
        when (state) {
            ConnectionState.Idle -> "not opened"
            is ConnectionState.Connecting -> "connecting, attempt ${state.attempt}"
            is ConnectionState.Open -> "open"
            is ConnectionState.Reconnecting -> "reconnecting, attempt ${state.attempt}"
            is ConnectionState.Ended -> "ended"
        }

    /** An ERROR with [code] and [message], without a location. */
    private fun error(code: String, message: String): DeployProblem = DeployProblem(Severity.ERROR, code, message, null)
}
