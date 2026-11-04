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

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import io.karpfen.ide.runtime.session.problem.DeployProblem

/**
 * Where a session stands. It follows the lifecycle phase of its environment once the deployment ended: activated is
 * [Ready], running is [Running], an engine that ended on its own is [Halted], a stopped environment is [Stopped]; a
 * failed deployment and an environment the runtime no longer knows are [Failed]. Immutable.
 */
public sealed interface SessionState {
    /** The deployment runs; nothing but shutting down and closing is possible yet. */
    public data object Deploying : SessionState

    /**
     * The environment is activated and not started: events wait until the start.
     *
     * @property environment the environment's key.
     */
    public data class Ready(public val environment: EnvironmentKey) : SessionState

    /**
     * The environment runs.
     *
     * @property environment the environment's key.
     */
    public data class Running(public val environment: EnvironmentKey) : SessionState

    /**
     * The environment was started, but its engine ended on its own; the runtime's console log has the reason.
     *
     * @property environment the environment's key.
     */
    public data class Halted(public val environment: EnvironmentKey) : SessionState

    /**
     * The session stops the environment.
     *
     * @property environment the environment's key.
     */
    public data class Stopping(public val environment: EnvironmentKey) : SessionState

    /**
     * The environment is stopped, for good: running again deploys a fresh environment.
     *
     * @property environment the environment's key.
     */
    public data class Stopped(public val environment: EnvironmentKey) : SessionState

    /**
     * The deployment failed, or the session lost its environment or its connection for good.
     *
     * @property problem why.
     * @property leftBehind the ledger entry of the environment that stays on the runtime, or null if none stays.
     */
    public data class Failed(public val problem: DeployProblem, public val leftBehind: LedgerEntry?) : SessionState

    /** The session was closed; it does nothing any more. */
    public data object Closed : SessionState
}

/** How a command of a session ended. Immutable. */
public sealed interface SessionCommandOutcome {
    /** The command was carried out. */
    public data object Done : SessionCommandOutcome

    /**
     * The command was not carried out: the session's state does not allow it, or the runtime refused it.
     *
     * @property state the session's state when the command was refused.
     * @property reason why, in a plain sentence, the runtime's own text where it refused.
     */
    public data class Refused(public val state: SessionState, public val reason: String) : SessionCommandOutcome

    /**
     * The command failed.
     *
     * @property problem why.
     */
    public data class Failed(public val problem: DeployProblem) : SessionCommandOutcome
}
