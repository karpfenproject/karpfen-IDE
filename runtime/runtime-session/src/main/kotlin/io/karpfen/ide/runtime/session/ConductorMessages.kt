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

import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.deploy.DeploymentResult
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * What the conductor of a session takes, in the order it arrives: the public commands with their replies, and the
 * reports of the deployment, the environment handle, the router and the conductor's own stop. Immutable as far as its
 * values are.
 */
internal sealed interface ConductorMessage {
    /** The reply a caller awaits, or null for a report. */
    val reply: CompletableDeferred<*>?
        get() = null

    /**
     * A public command.
     *
     * @property command what to do.
     */
    data class Command(val command: SessionCommand, override val reply: CompletableDeferred<SessionCommandOutcome>) : ConductorMessage

    /**
     * An event frame the injector sends.
     *
     * @property frame the frame.
     */
    data class Inject(val frame: EventFrame, override val reply: CompletableDeferred<SendOutcome>) : ConductorMessage

    /** The request to end the session cleanly, answered once nothing is left to stop. */
    data class Shutdown(override val reply: CompletableDeferred<Unit>) : ConductorMessage

    /**
     * The deployment's progress changed.
     *
     * @property progress the progress.
     */
    data class Progressed(val progress: DeployProgress) : ConductorMessage

    /**
     * The deployment created the environment.
     *
     * @property environment its handle.
     */
    data class EnvironmentCreated(val environment: RuntimeEnvironment) : ConductorMessage

    /**
     * The deployment ended.
     *
     * @property result how.
     */
    data class DeployEnded(val result: DeploymentResult) : ConductorMessage

    /**
     * The deployment broke with an exception it should never have thrown; it cleaned up before.
     *
     * @property error the exception.
     */
    data class DeployBroke(val error: Throwable) : ConductorMessage

    /**
     * The environment's phase changed, as its handle keeps it.
     *
     * @property phase the new phase.
     */
    data class PhaseChanged(val phase: EnvironmentPhase) : ConductorMessage

    /**
     * The connection ended for good.
     *
     * @property end why.
     */
    data class ConnectionEnded(val end: ConnectionEnd) : ConductorMessage

    /**
     * The connection's state changed.
     *
     * @property state the new state.
     */
    data class ConnectionChanged(val state: ConnectionState) : ConductorMessage

    /**
     * The observation ran into a problem, such as a model it could not re-synchronize.
     *
     * @property problem the problem.
     */
    data class ObservationProblem(val problem: DeployProblem) : ConductorMessage

    /**
     * The conductor's own stop of the environment ended.
     *
     * @property outcome the outcome of the stop call, or null if it took longer than its bound.
     * @property leftBehind the ledger entry the stop recorded, or null if it recorded none.
     */
    data class StopFinished(val outcome: CallOutcome<Unit>?, val leftBehind: LedgerEntry?) : ConductorMessage
}

/**
 * The unbounded inbox of a session's conductor. Everybody hands messages in without suspending; only the conductor
 * takes them out.
 */
internal class ConductorInbox {
    /** The messages, unbounded. */
    private val channel = Channel<ConductorMessage>(Channel.UNLIMITED)

    /** The messages in arrival order, for the conductor. */
    val messages: ReceiveChannel<ConductorMessage>
        get() = channel

    /** Hands [message] in; false once the inbox is closed. */
    fun offer(message: ConductorMessage): Boolean = channel.trySend(message).isSuccess

    /** Closes the inbox: no message is taken any more. */
    fun close() {
        channel.close()
    }
}
