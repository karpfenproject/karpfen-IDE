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
package io.karpfen.ide.bridges.api

import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * The [ModelLink] over a connection of the runtime protocol. A bridge sends its events through [connection] and reads
 * the pushes that arrive on it.
 *
 * The runtime only pushes what the client of the connection asked for. So whoever opens the connection must have
 * registered that client as a listener of the event domains and as an observer of the model, as far as the outbound
 * routes of the bridge need it. The runtime takes listeners of event domains only before the environment is
 * activated.
 */
public class RuntimeModelLink(private val connection: RuntimeConnection) : ModelLink {

    override val pushes: Flow<RuntimePush> = connection.events.filterIsInstance<ConnectionEvent.Received>().map { it.push }

    override suspend fun send(event: EventFrame): Delivery = when (val outcome = connection.send(event)) {
        is SendOutcome.Written -> Delivery.Delivered
        is SendOutcome.NotConnected -> Delivery.Failed("The connection to the runtime is not open.")
        is SendOutcome.NotAllowed -> Delivery.Failed(outcome.refusal.reason)
        is SendOutcome.Failed -> Delivery.Failed("The event could not be sent to the runtime: ${outcome.failure.cause}")
    }
}
