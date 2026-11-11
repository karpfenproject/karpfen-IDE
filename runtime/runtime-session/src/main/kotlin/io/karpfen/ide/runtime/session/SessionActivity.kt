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

import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.message.PublishedEvent
import io.karpfen.ide.runtime.session.deploy.StepProgress
import io.karpfen.ide.runtime.session.observation.SyncReason
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlin.time.Duration

/**
 * An event that was published on a domain the session listens to, as the runtime announced it. Immutable.
 *
 * @property domain the event's domain.
 * @property event the event, or null if the runtime's announcement lacked a member or had one of the wrong type.
 * @property rawPayload the announcement's inner payload as it came, written compactly.
 * @property elapsed the time since the session was launched when it arrived.
 */
public data class ObservedDomainEvent(
    public val domain: String,
    public val event: PublishedEvent?,
    public val rawPayload: String,
    public val elapsed: Duration,
)

/**
 * One entry of a session's journal. It tells what the session did and what happened to it, for a log view. Every entry
 * carries the time since the session was launched. Immutable.
 */
public sealed interface SessionActivity {
    /** The time since the session was launched. */
    public val elapsed: Duration

    /**
     * A step of the deployment ended. It was done, skipped or failed.
     *
     * @property step the step with its final status.
     */
    public data class StepFinished(public val step: StepProgress, override val elapsed: Duration) : SessionActivity

    /**
     * A call of a command failed or was refused by the runtime.
     *
     * @property problem why.
     */
    public data class CallFailed(public val problem: DeployProblem, override val elapsed: Duration) : SessionActivity

    /**
     * An event frame was written to the socket.
     *
     * @property frameText the frame as written.
     */
    public data class EventSent(public val frameText: String, override val elapsed: Duration) : SessionActivity

    /**
     * A push of the runtime was applied to the observed model.
     *
     * @property messageType the push's message type, such as `objectChanged`.
     * @property ids the ids of the objects or the keys of the machines it concerned.
     * @property revision the push's revision.
     */
    public data class PushApplied(
        public val messageType: String,
        public val ids: List<String>,
        public val revision: Long,
        override val elapsed: Duration,
    ) : SessionActivity

    /**
     * A domain event arrived.
     *
     * @property event the event.
     */
    public data class DomainEventReceived(public val event: ObservedDomainEvent, override val elapsed: Duration) : SessionActivity

    /**
     * The observed model was read from a snapshot to get in step with the runtime again.
     *
     * @property reason why the snapshot was read.
     * @property revision the snapshot's revision.
     */
    public data class Synchronized(public val reason: SyncReason, public val revision: Long, override val elapsed: Duration) : SessionActivity

    /**
     * Something happened that should not, such as a revision gap or a frame that broke the protocol.
     *
     * @property detail what, in a plain sentence.
     */
    public data class Anomaly(public val detail: String, override val elapsed: Duration) : SessionActivity

    /**
     * The connection's state changed.
     *
     * @property state the new state.
     */
    public data class ConnectionChanged(public val state: ConnectionState, override val elapsed: Duration) : SessionActivity

    /**
     * The session raised a problem.
     *
     * @property problem the problem.
     */
    public data class ProblemRaised(public val problem: DeployProblem, override val elapsed: Duration) : SessionActivity

    /**
     * Something worth knowing, in a plain sentence.
     *
     * @property text what.
     */
    public data class Note(public val text: String, override val elapsed: Duration) : SessionActivity
}
