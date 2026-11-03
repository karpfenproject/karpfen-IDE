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
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.connection.AccessKeyIssuer
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import kotlinx.coroutines.CoroutineScope

/**
 * The client of one karpfen runtime with IDE support: checks the runtime, lists and creates environments and opens
 * connections to them. Everything that concerns one environment goes through its [RuntimeEnvironment] handle, which
 * keeps the environment's lifecycle; pushes and injected events go through a [RuntimeConnection].
 *
 * Threading (MUST): every function may be called from any coroutine; the client runs its calls on the dispatcher of
 * its options, so every delay and timeout runs there (virtual time in tests). It owns the transports and no coroutines.
 *
 * Contract (MUST):
 * - No function throws for a runtime or transport failure; failures are results.
 * - [close] closes the transports and leaves the environments on the runtime as they are; it is idempotent.
 * - A `CancellationException` of the caller is always rethrown.
 */
public interface RuntimeClient : AutoCloseable {
    /** The runtime the client talks to. */
    public val endpoint: RuntimeEndpoint

    /** Asks the runtime whether it is up (`GET /health`). */
    public suspend fun health(): RuntimeResult<Unit>

    /**
     * Checks that the runtime has the IDE's routes: the snapshot route answers a call without parameters with its text
     * for a missing `envKey`. Any other answer, a 404 among them, is a failure: the runtime lacks IDE support.
     */
    public suspend fun checkIdeSupport(): RuntimeResult<Unit>

    /** The environments the runtime lists: activated, running, halted and stopped ones. */
    public suspend fun activeEnvironments(): RuntimeResult<List<ActiveEnvironment>>

    /** Creates an environment and answers its handle, in the phase `Configuring` with nothing uploaded. */
    public suspend fun createEnvironment(): RuntimeResult<RuntimeEnvironment>

    /**
     * A connection of [clientId] to [environment], not opened yet, running in a supervisor child of [scope]. Its access
     * keys come from [issuer], by default from the handle without an observatory registration.
     */
    public fun connection(
        environment: RuntimeEnvironment,
        clientId: ClientId,
        scope: CoroutineScope,
        issuer: AccessKeyIssuer? = null,
    ): RuntimeConnection
}
