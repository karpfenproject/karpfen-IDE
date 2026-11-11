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

import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.connection.ConnectionState

/**
 * Checks the runtime before anything is created. It checks that the runtime is up with `GET /health`. Then it checks
 * that the runtime has the IDE's routes by calling the snapshot route without parameters. That way a runtime without
 * IDE support fails fast.
 *
 * @param endpoint the runtime, named in the title.
 */
internal class CheckRuntimeStep(endpoint: RuntimeEndpoint) : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.CHECK_RUNTIME
    override val title: String = "Checking the runtime at ${endpoint.httpBase}"

    override suspend fun execute(run: DeployRun): StepOutcome {
        val client = run.request.client
        (client.health() as? RuntimeResult.Failed)?.let { return run.failed(it.failure) }
        run.report("The runtime is up; checking its IDE support")
        (client.checkIdeSupport() as? RuntimeResult.Failed)?.let { return run.failed(it.failure) }
        return StepOutcome.Done("The runtime is up and supports the IDE")
    }
}

/** Creates the environment and reports it, so that the cleanup knows it from now on. */
internal class CreateEnvironmentStep : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.CREATE
    override val title: String = "Creating the environment"

    override suspend fun execute(run: DeployRun): StepOutcome {
        run.creationStarts()
        return when (val created = run.request.client.createEnvironment()) {
            is RuntimeResult.Ok -> {
                run.created(created.value)
                StepOutcome.Done(created.value.key.value)
            }
            is RuntimeResult.Failed -> {
                run.creationFailed(created.failure)
                run.failed(created.failure)
            }
        }
    }
}

/**
 * Connects to the environment right after its creation. The runtime never acknowledges the handshake. Connecting this
 * early means that the many calls before the activation separate the handshake from the first push. The connection is
 * reported before it is opened, so that the session collects its events from the first opening on.
 */
internal class ConnectStep : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.CONNECT
    override val title: String = "Connecting to the environment"

    override suspend fun execute(run: DeployRun): StepOutcome {
        val request = run.request
        val connection = request.client.connection(run.createdEnvironment, request.clientId, request.connectionScope)
        run.connected(connection)
        return when (val state = connection.open()) {
            is ConnectionState.Open -> StepOutcome.Done("Connected as ${request.clientId}")
            is ConnectionState.Ended -> StepOutcome.Failed(ConnectionProblems.ended(state.end), null)
            else -> StepOutcome.Failed(ConnectionProblems.notOpen(state, run.timing.connectionWait), null)
        }
    }
}
