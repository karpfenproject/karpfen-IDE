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
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.Deployer
import io.karpfen.ide.runtime.session.deploy.EnvironmentLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The default [RuntimeSessions]. It prepares deployments on the dispatcher of the options. All sessions it launches
 * record in one ledger and deploy with one deployer.
 *
 * @param options how the sessions run.
 * @param ledger where the sessions record the environments they leave.
 * @param preparer prepares deployments.
 * @param deployer runs the deployments.
 */
internal class DefaultRuntimeSessions(
    private val options: RuntimeSessionOptions,
    private val ledger: EnvironmentLedger,
    private val preparer: DeploymentPreparer,
    private val deployer: Deployer,
) : RuntimeSessions {

    override suspend fun prepare(workspace: Workspace, descriptor: Path): Preparation =
        withContext(options.dispatcher) { preparer.prepare(workspace, descriptor) }

    override fun launch(
        prepared: PreparedDeployment,
        client: RuntimeClient,
        scope: CoroutineScope,
        options: DeployOptions,
    ): RuntimeSession =
        DefaultRuntimeSession(prepared, client, scope, options, this.options, ledger, deployer).also { it.begin() }
}
