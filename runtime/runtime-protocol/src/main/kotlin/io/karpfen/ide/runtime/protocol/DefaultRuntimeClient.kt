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
package io.karpfen.ide.runtime.protocol

import io.karpfen.ide.runtime.protocol.api.ActiveEnvironment
import io.karpfen.ide.runtime.protocol.api.HttpRuntimeApi
import io.karpfen.ide.runtime.protocol.api.RuntimeApi
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.api.map
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.connection.AccessKeyIssuer
import io.karpfen.ide.runtime.protocol.connection.DefaultRuntimeConnection
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.lifecycle.DefaultRuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Observatory
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * The client built on [transports]. It puts the gateway on their HTTP seam, the environment handles on the gateway, and
 * the connections on their WebSocket seam. Every suspend function runs on the dispatcher of [options], so every delay
 * and timeout runs there. The client owns the transports and no coroutines. Closing it closes the transports and leaves
 * the environments as they are.
 *
 * @param options how the client runs.
 * @param transports the seams to the runtime.
 */
internal class DefaultRuntimeClient(
    override val endpoint: RuntimeEndpoint,
    private val options: RuntimeClientOptions,
    private val transports: RuntimeTransports,
) : RuntimeClient {

    /** The gateway every handle and the client itself call through. */
    private val api: RuntimeApi = HttpRuntimeApi(transports.http, options.timeouts, options.timeSource)

    override suspend fun health(): RuntimeResult<Unit> = withContext(options.dispatcher) { api.health() }

    override suspend fun checkIdeSupport(): RuntimeResult<Unit> = withContext(options.dispatcher) { api.probeSnapshotRoute() }

    override suspend fun activeEnvironments(): RuntimeResult<List<ActiveEnvironment>> =
        withContext(options.dispatcher) { api.listEnvironments() }

    override suspend fun createEnvironment(): RuntimeResult<RuntimeEnvironment> =
        withContext(options.dispatcher) {
            api.createEnvironment().map { key -> DefaultRuntimeEnvironment(key, api, options.dispatcher) }
        }

    override fun connection(
        environment: RuntimeEnvironment,
        clientId: ClientId,
        scope: CoroutineScope,
        issuer: AccessKeyIssuer?,
    ): RuntimeConnection =
        DefaultRuntimeConnection(
            handle = environment,
            clientId = clientId,
            webSocketUrl = endpoint.webSocketUrl,
            connector = transports.webSockets,
            issuer = issuer ?: AccessKeyIssuer { environment.issueAccessKey(clientId, Observatory.NoMachine) },
            policy = options.reconnect,
            parent = scope,
            dispatcher = options.dispatcher,
        )

    override fun close() {
        transports.close()
    }
}
