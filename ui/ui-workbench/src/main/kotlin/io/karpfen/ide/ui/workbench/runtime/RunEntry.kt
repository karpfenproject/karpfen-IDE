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

import io.karpfen.ide.runtime.host.HostProblem
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.problem.DeployProblem
import java.nio.file.Path

/** Where a run stands: first its own steps, then, once launched, the state of its session. */
public enum class RunPhase {
    /** The deployment is being prepared from the saved workspace. */
    PREPARING,

    /** The preparation found errors; the run never reached the runtime. */
    BLOCKED,

    /** The run waits for the runtime to start. */
    WAITING_FOR_RUNTIME,

    /** The session deploys the environment. */
    DEPLOYING,

    /** The environment runs. */
    RUNNING,

    /** The environment is activated but not started. */
    READY,

    /** The environment was stopped, or the session ended without failing. */
    STOPPED,

    /** The runtime, the deployment or the environment failed. */
    FAILED,
    ;

    /** True for a run that is over: blocked, stopped or failed. */
    public val isFinished: Boolean
        get() = this == BLOCKED || this == STOPPED || this == FAILED

    public companion object {
        /**
         * The phase of a run whose session is in [state], given the phase [before] it: an environment that ended on its
         * own counts as failed, a closed session keeps a finished phase and counts as stopped otherwise.
         */
        public fun of(state: SessionState, before: RunPhase): RunPhase = when (state) {
            SessionState.Deploying -> DEPLOYING
            is SessionState.Ready -> READY
            is SessionState.Running -> RUNNING
            is SessionState.Halted, is SessionState.Failed -> FAILED
            is SessionState.Stopping, is SessionState.Stopped -> STOPPED
            SessionState.Closed -> if (before.isFinished) before else STOPPED
        }
    }
}

/**
 * One run of an environment descriptor, as the Runtime tool window lists it. Immutable; the runtime state replaces an
 * entry when its run moves on.
 *
 * @property id identifies the run within its workspace.
 * @property descriptor the environment descriptor that runs.
 * @property planName the name of the run, the descriptor's file name without its suffix.
 * @property phase where the run stands.
 * @property findings the findings of the preparation that blocked the run, empty otherwise.
 * @property hostProblem why the runtime could not be started, or null.
 * @property session the session of the run once it was launched, or null before.
 * @property environment the key of the deployed environment once the session knows it, or null.
 */
public data class RunEntry(
    public val id: Long,
    public val descriptor: Path,
    public val planName: String,
    public val phase: RunPhase,
    public val findings: List<DeployProblem> = emptyList(),
    public val hostProblem: HostProblem? = null,
    public val session: RuntimeSession? = null,
    public val environment: EnvironmentKey? = null,
) {
    /** True while the environment of the run can be stopped: it is activated or runs. */
    public val isStoppable: Boolean
        get() = phase == RunPhase.READY || phase == RunPhase.RUNNING

    /** True while the run still deploys or holds an environment that is not stopped. */
    public val isLive: Boolean
        get() = phase == RunPhase.DEPLOYING || isStoppable

    public companion object {
        /** The suffix of environment descriptors, which the name of a run leaves out. */
        public const val DESCRIPTOR_SUFFIX: String = ".kenv.json"

        /** The name of a run of [descriptor]: its file name without [DESCRIPTOR_SUFFIX]. */
        public fun nameOf(descriptor: Path): String = descriptor.fileName.toString().removeSuffix(DESCRIPTOR_SUFFIX)
    }
}
