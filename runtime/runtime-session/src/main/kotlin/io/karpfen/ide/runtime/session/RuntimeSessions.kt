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

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.Deployer
import io.karpfen.ide.runtime.session.deploy.EnvironmentLedger
import io.karpfen.ide.runtime.session.plan.DeploymentPlanner
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Prepares deployments from a workspace and launches sessions that deploy them: the entry point of deploying and
 * observing an environment.
 *
 * Threading (MUST): every function may be called from any coroutine; [prepare] runs on the dispatcher of the options.
 *
 * Contract (MUST): failures are results, never exceptions; a `CancellationException` of the caller is rethrown.
 */
public interface RuntimeSessions {
    /**
     * Prepares the deployment of [descriptor] from [workspace]: saves every open document, because a deployment uploads
     * the saved files; awaits the analysis; refuses when a file of the context or the descriptor still differs from its
     * saved state; plans the deployment, with the problems gate on exactly the analyzed texts it uploads; and turns the
     * analysis into the immutable values a session needs. Nothing it answers holds a dsl-tools object.
     */
    public suspend fun prepare(workspace: Workspace, descriptor: Path): Preparation

    /**
     * Launches a session that deploys [prepared] to the runtime of [client] as [options] say, in a supervisor child of
     * [scope]. Returns at once, in [SessionState.Deploying], with a fresh client id.
     */
    public fun launch(
        prepared: PreparedDeployment,
        client: RuntimeClient,
        scope: CoroutineScope,
        options: DeployOptions = DeployOptions(),
    ): RuntimeSession
}

/**
 * The sessions that run as [options] say, record every environment they leave on a runtime in [ledger], plan with
 * [planner] and deploy with [deployer], by default one that records in [ledger] with the deploy timing of [options].
 * The composition root holds one [ledger] per process.
 */
public fun RuntimeSessions(
    options: RuntimeSessionOptions = RuntimeSessionOptions(),
    ledger: EnvironmentLedger = EnvironmentLedger(),
    planner: DeploymentPlanner = DeploymentPlanner(),
    deployer: Deployer = Deployer(ledger, options.deployTiming),
): RuntimeSessions = DefaultRuntimeSessions(options, ledger, DeploymentPreparer(planner, EnvironmentDescriptorReader()), deployer)
