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

import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** Tests the model link over a connection of the runtime protocol. */
class RuntimeModelLinkTest {

    private val press = EventFrame("switch", "press", EventPayload.None)

    @Test
    fun `the pushes of the connection are the pushes of the link, and nothing else`() = runTest {
        val changed = RuntimePush.StateChanged("lamp", listOf("on"), 4)
        val connection = FakeConnection(events = flowOf(ConnectionEvent.Opened(1), ConnectionEvent.Received(changed)))

        assertEquals(listOf<RuntimePush>(changed), RuntimeModelLink(connection).pushes.toList())
    }

    @Test
    fun `an event that was written counts as delivered`() = runTest {
        val connection = FakeConnection(outcome = SendOutcome.Written("frame"))

        assertEquals(Delivery.Delivered, RuntimeModelLink(connection).send(press))
        assertEquals(listOf(press), connection.sent)
    }

    @Test
    fun `an event that could not be sent fails with the reason`() = runTest {
        val closed = RuntimeModelLink(FakeConnection(outcome = SendOutcome.NotConnected(ConnectionState.Idle)))
        val broken = RuntimeModelLink(FakeConnection(outcome = SendOutcome.Failed(TransportFailure.Unreachable("connection refused"))))

        assertEquals(Delivery.Failed("The connection to the runtime is not open."), closed.send(press))
        assertEquals(Delivery.Failed("The event could not be sent to the runtime: connection refused"), broken.send(press))
    }

    /** A connection that answers every send with [outcome] and reports [events]. */
    private class FakeConnection(
        override val events: Flow<ConnectionEvent> = flowOf(),
        private val outcome: SendOutcome = SendOutcome.Written(""),
    ) : RuntimeConnection {
        val sent = ArrayList<EventFrame>()
        override val environment = EnvironmentKey("env-1")
        override val clientId = ClientId("bridge-1")
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)

        override suspend fun open(): ConnectionState = state.value

        override suspend fun awaitOpen(within: Duration): ConnectionState = state.value

        override suspend fun send(frame: EventFrame): SendOutcome {
            sent += frame
            return outcome
        }

        override suspend fun disconnect() = Unit

        override fun close() = Unit
    }
}
