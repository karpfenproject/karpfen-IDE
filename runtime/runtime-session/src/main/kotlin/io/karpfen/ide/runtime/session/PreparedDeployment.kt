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

import io.karpfen.ide.runtime.session.events.EventCatalog
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.schema.TypeSchema
import io.karpfen.ide.runtime.session.states.MachineLayout

/**
 * Everything a session needs to deploy one environment descriptor and to show what runs, derived from the analysis of
 * the saved workspace before anything is launched. It holds no dsl-tools object, so it may cross threads. Immutable.
 *
 * @property plan what the deployment uploads, sets and registers, with the warnings and infos of its checks.
 * @property schema the types of the domain metamodel, with which the runtime's JSON is read.
 * @property seed the model as the IDE analyzed it: what the runtime is expected to report before anything ran.
 * @property layouts one layout per attached state machine, in descriptor order, with its predicted initial stack.
 * @property catalog the injectable events of every event domain the state machines use.
 */
public data class PreparedDeployment(
    public val plan: DeploymentPlan,
    public val schema: TypeSchema,
    public val seed: MirrorSnapshot,
    public val layouts: List<MachineLayout>,
    public val catalog: EventCatalog,
)

/** What preparing a deployment answers: a prepared deployment, or the problems that block it. Immutable. */
public sealed interface Preparation {
    /**
     * The deployment can be launched.
     *
     * @property prepared what it deploys.
     */
    public data class Ready(public val prepared: PreparedDeployment) : Preparation

    /**
     * The deployment must not run.
     *
     * @property findings every finding of the preparation, at least one of them an error.
     */
    public data class Blocked(public val findings: List<DeployProblem>) : Preparation
}
