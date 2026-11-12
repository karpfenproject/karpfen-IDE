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

import io.karpfen.ide.runtime.protocol.message.WireValue

/**
 * The external systems a bridge can connect to.
 *
 * @property id the name of the kind in a bridge file.
 */
public enum class BridgeKind(public val id: String) {
    /** Plain HTTP requests in both directions. */
    HTTP("http"),

    /** Topics of an MQTT broker. */
    MQTT("mqtt"),

    /** Topics of ROS, reached through a rosbridge server. */
    ROS("ros"),
    ;

    public companion object {
        /** The kind named [id] in a bridge file, or null if there is none. The case does not matter. */
        public fun of(id: String): BridgeKind? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * What one bridge does. It is the content of one bridge file of a project.
 *
 * @property kind the external system the bridge connects to.
 * @property settings what the bridge needs to reach that system, by name. Which names there are depends on the
 *   [kind], for example `port` for HTTP or `broker` for MQTT. All values are text.
 * @property inbound the routes from the external system into the model, in the order of the file.
 * @property outbound the routes from the model to the external system, in the order of the file.
 */
public data class BridgeConfig(
    public val kind: BridgeKind,
    public val settings: Map<String, String> = emptyMap(),
    public val inbound: List<InboundRoute> = emptyList(),
    public val outbound: List<OutboundRoute> = emptyList(),
)

/**
 * A route from the external system into the model. Every message that arrives at [from] becomes one event of the
 * model.
 *
 * @property from where the message arrives, in the terms of the external system: a path for HTTP, a topic for MQTT
 *   and ROS.
 * @property domain the domain of the event.
 * @property event the name of the event.
 * @property payload how the payload of the event is made from the message. Null hands the body of the message on as
 *   it is. An empty list sends the event without a payload.
 * @property flow what happens when messages arrive faster than the model takes them.
 */
public data class InboundRoute(
    public val from: String,
    public val domain: String,
    public val event: String,
    public val payload: List<PayloadField>? = null,
    public val flow: FlowPolicy = FlowPolicy.Queue(),
)

/**
 * One field of the payload of an event.
 *
 * @property name the name of the field in the payload.
 * @property source where its value comes from.
 */
public data class PayloadField(public val name: String, public val source: ValueSource)

/** Where the value of a [PayloadField] comes from. */
public sealed interface ValueSource {
    /**
     * A place in the JSON body of the message.
     *
     * @property path the path to the value.
     */
    public data class FromMessage(public val path: JsonPath) : ValueSource

    /**
     * A fixed value that is the same for every message.
     *
     * @property value the value.
     */
    public data class Fixed(public val value: WireValue) : ValueSource
}

/**
 * A route from the model to the external system. Whenever [source] has something new, one message goes to [to].
 *
 * @property source what of the model the route follows.
 * @property to where the message goes, in the terms of the external system: a URL for HTTP, a topic for MQTT and ROS.
 * @property flow what happens when the model changes faster than the external system takes the messages.
 */
public data class OutboundRoute(
    public val source: OutboundSource,
    public val to: String,
    public val flow: FlowPolicy = FlowPolicy.Queue(),
)

/** What of the model an [OutboundRoute] follows. */
public sealed interface OutboundSource {
    /**
     * A property of an object. A message goes out when its value changes, with the new value as JSON.
     *
     * @property objectId the key of the object.
     * @property property the name of the property.
     */
    public data class Property(public val objectId: String, public val property: String) : OutboundSource

    /**
     * The active states of a state machine. A message goes out when they change, with the names of the active states
     * as a JSON array, outermost first.
     *
     * @property machine the key of the object the state machine is attached to.
     */
    public data class State(public val machine: String) : OutboundSource

    /**
     * An event that a state machine raises. A message goes out for every such event, with the payload of the event.
     *
     * @property domain the domain of the event.
     * @property event the name of the event.
     */
    public data class Event(public val domain: String, public val event: String) : OutboundSource
}

/**
 * What a route does when messages come faster than they can be handed on. The engine of the runtime works in ticks
 * and events expire, so a route must say what may be lost.
 */
public sealed interface FlowPolicy {
    /**
     * Keeps the messages in their order. When more than [limit] wait, the oldest one is dropped.
     *
     * @property limit how many messages may wait.
     */
    public data class Queue(public val limit: Int = DEFAULT_LIMIT) : FlowPolicy {
        init {
            require(limit > 0) { "limit must be positive, was $limit" }
        }
    }

    /** Keeps only the newest message. A message that still waits when the next one arrives is dropped. */
    public data object Latest : FlowPolicy

    /**
     * Hands on at most one message per period, always the newest one.
     *
     * @property periodMs the period in milliseconds.
     */
    public data class Sample(public val periodMs: Long) : FlowPolicy {
        init {
            require(periodMs > 0) { "periodMs must be positive, was $periodMs" }
        }
    }

    public companion object {
        /** How many messages a queue holds unless the route says otherwise. */
        public const val DEFAULT_LIMIT: Int = 64
    }
}
