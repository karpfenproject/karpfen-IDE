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

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.session.problem.DeployProblem

/**
 * One step of a deployment, as a command the pipeline runs. It makes its calls through the [DeployRun] and returns how
 * it went. A step never throws for a failure of the runtime. A cancellation of the caller propagates.
 */
internal interface DeployStep {
    /** What the step does. */
    val kind: DeployStepKind

    /** What it concerns, in words. */
    val title: String

    /** Runs the step in [run]. */
    suspend fun execute(run: DeployRun): StepOutcome
}

/** How a step went. Immutable. */
internal sealed interface StepOutcome {
    /**
     * The step did its work.
     *
     * @property detail what it did, in words, or null.
     * @property findings warnings the step adds to the deployment's result.
     */
    data class Done(val detail: String? = null, val findings: List<DeployProblem> = emptyList()) : StepOutcome

    /**
     * The step is not needed for this deployment.
     *
     * @property detail why, in words.
     */
    data class Skipped(val detail: String) : StepOutcome

    /**
     * The step failed. The deployment stops here.
     *
     * @property problem the problem the failure is.
     * @property failure the runtime's failure behind it, or null.
     */
    data class Failed(val problem: DeployProblem, val failure: RuntimeFailure?) : StepOutcome
}
