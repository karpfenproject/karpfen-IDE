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

import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A message of the external system.
 *
 * @property address where it arrived or where it goes. For a message that arrived, it is the `from` of the route it
 *   belongs to, so a bridge finds the route without knowing how the external system matches addresses.
 * @property body the content, usually JSON.
 */
public data class ExternalMessage(public val address: String, public val body: String)

/** Whether a message reached the other side. */
public sealed interface Delivery {
    /** It did. */
    public data object Delivered : Delivery

    /**
     * It did not.
     *
     * @property reason why, in words for the user.
     */
    public data class Failed(public val reason: String) : Delivery
}

/**
 * The external side of a bridge: an HTTP server and client, an MQTT broker, or ROS. Each kind of bridge brings one
 * implementation. A bridge opens its endpoint once, reads what arrives from [incoming], publishes through [publish],
 * and closes it at the end.
 */
public interface BridgeEndpoint {

    /** The messages that arrive for the addresses given to [open]. The flow never completes on its own. */
    public val incoming: Flow<ExternalMessage>

    /**
     * Connects to the external system and starts to listen at [addresses], which are the `from` of the inbound routes.
     * Returns why if that is not possible.
     */
    public suspend fun open(addresses: Set<String>): Delivery

    /** Sends [message] to the external system. */
    public suspend fun publish(message: ExternalMessage): Delivery

    /** Stops listening and lets go of the external system. An endpoint that is not open stays as it is. */
    public suspend fun close()
}

/**
 * The model side of a bridge: one running environment of a karpfen runtime. It speaks in the terms of the runtime
 * protocol. A bridge sends events into the environment and follows what the environment reports.
 */
public interface ModelLink {

    /** Everything the environment reports: changed objects, changed states and events of the state machines. */
    public val pushes: Flow<RuntimePush>

    /** Sends [event] into the environment. */
    public suspend fun send(event: EventFrame): Delivery
}

/** What a bridge is doing. */
public enum class BridgeState {
    /** Not started, or stopped again. */
    STOPPED,

    /** Connecting to the external system. */
    STARTING,

    /** Connected and handing messages on. */
    RUNNING,

    /** Could not start. [BridgeStatus.problem] says why. */
    FAILED,
}

/**
 * How one route of a bridge is doing.
 *
 * @property label the route in a few words, such as `/dimmer to switch/dim`.
 * @property received how many messages arrived for the route.
 * @property delivered how many of them reached the other side.
 * @property dropped how many were dropped by the flow policy of the route.
 * @property failed how many could not be mapped or delivered.
 * @property lastError why the last failure happened, or null if there was none.
 */
public data class RouteStatus(
    public val label: String,
    public val received: Long = 0,
    public val delivered: Long = 0,
    public val dropped: Long = 0,
    public val failed: Long = 0,
    public val lastError: String? = null,
)

/**
 * How a bridge is doing.
 *
 * @property state what the bridge is doing.
 * @property problem why the bridge failed, or null.
 * @property inbound its inbound routes in the order of the configuration.
 * @property outbound its outbound routes in the order of the configuration.
 */
public data class BridgeStatus(
    public val state: BridgeState = BridgeState.STOPPED,
    public val problem: String? = null,
    public val inbound: List<RouteStatus> = emptyList(),
    public val outbound: List<RouteStatus> = emptyList(),
)

/**
 * A bridge between one running environment and one external system. It is a plain component with a lifecycle, so it
 * runs the same way inside the IDE and in the headless runner.
 */
public interface Bridge {

    /** What the bridge is doing and how its routes are doing. */
    public val status: StateFlow<BridgeStatus>

    /**
     * Connects to the external system and starts to hand messages on in both directions. If the external system
     * cannot be reached, the bridge ends up [BridgeState.FAILED]. Starting a bridge that runs does nothing.
     */
    public suspend fun start()

    /** Stops handing messages on and lets go of the external system. Messages that still wait are dropped. */
    public suspend fun stop()
}
