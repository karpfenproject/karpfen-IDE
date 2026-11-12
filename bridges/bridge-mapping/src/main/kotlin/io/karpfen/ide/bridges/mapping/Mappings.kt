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
package io.karpfen.ide.bridges.mapping

import io.karpfen.ide.bridges.api.ExternalMessage
import io.karpfen.ide.bridges.api.InboundRoute
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.bridges.api.OutboundRoute
import io.karpfen.ide.bridges.api.OutboundSource
import io.karpfen.ide.bridges.api.ValueSource
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireField
import io.karpfen.ide.runtime.protocol.message.WireValue

/** What became of a message on an inbound route. */
public sealed interface InboundMapping {
    /**
     * The message is this event.
     *
     * @property event the event for the model.
     */
    public data class Mapped(public val event: EventFrame) : InboundMapping

    /**
     * The message cannot become the event of the route.
     *
     * @property reason why, in words for the user.
     */
    public data class Rejected(public val reason: String) : InboundMapping
}

/**
 * The two translations of a bridge. [toEvent] makes the event of an inbound route out of a message of the external
 * system. [toMessage] makes the message of an outbound route out of something the model reported.
 */
public object Mappings {

    /**
     * The event that [message] becomes on [route].
     *
     * A route without a payload mapping hands the body on. A body that is a JSON object becomes the payload, and any
     * other body, an empty one included, gives an event without a payload. A route with a payload mapping builds the
     * payload field by field. For that the body must be JSON, and every path must lead to a value.
     */
    public fun toEvent(route: InboundRoute, message: ExternalMessage): InboundMapping {
        val fields = route.payload
        if (fields == null) {
            val body = message.body.trim()
            val payload = if (JsonText.read(body) is WireValue.WireObject) EventPayload.Json(body) else EventPayload.None
            return InboundMapping.Mapped(EventFrame(route.domain, route.event, payload))
        }
        if (fields.isEmpty()) return InboundMapping.Mapped(EventFrame(route.domain, route.event, EventPayload.None))
        val needsBody = fields.any { it.source is ValueSource.FromMessage }
        val body = JsonText.read(message.body)
        if (needsBody && body == null) return InboundMapping.Rejected("The message is not JSON.")
        val values = fields.map { field ->
            val value = when (val source = field.source) {
                is ValueSource.Fixed -> source.value
                is ValueSource.FromMessage -> source.path.read(body ?: WireValue.WireNull)
                    ?: return InboundMapping.Rejected("The message has nothing at ${source.path.text} for \"${field.name}\".")
            }
            WireField(field.name, value)
        }
        return InboundMapping.Mapped(EventFrame(route.domain, route.event, EventPayload.Json(JsonText.write(WireValue.WireObject(values)))))
    }

    /**
     * The message that [push] gives on [route], or null if the push is not what the route follows.
     *
     * A property route answers a change of its object with the value of its property as JSON. A state route answers a
     * change of its state machine with the names of the active states as a JSON array. An event route answers its
     * event with the payload of the event, or with an empty JSON object if the event has none.
     */
    public fun toMessage(route: OutboundRoute, push: RuntimePush): ExternalMessage? {
        val body = when (val source = route.source) {
            is OutboundSource.Property ->
                (push as? RuntimePush.ObjectChanged)?.takeIf { it.objectId == source.objectId }?.value?.get(source.property)?.let(JsonText::write)
            is OutboundSource.State ->
                (push as? RuntimePush.StateChanged)
                    ?.takeIf { it.modelElementId == source.machine }
                    ?.let { JsonText.write(WireValue.WireArray(it.stack.map(WireValue::WireString))) }
            is OutboundSource.Event ->
                (push as? RuntimePush.DomainEvent)
                    ?.takeIf { it.domain == source.domain && it.event?.name == source.event }
                    ?.let { it.event?.payload?.takeIf(String::isNotBlank) ?: "{}" }
        } ?: return null
        return ExternalMessage(route.to, body)
    }

    /** [route] in a few words for a status display, such as `/dimmer to switch/dim`. */
    public fun label(route: InboundRoute): String = "${route.from} to ${route.domain}/${route.event}"

    /** [route] in a few words for a status display, such as `bulb/watts to home/lamp/watts`. */
    public fun label(route: OutboundRoute): String {
        val source = when (val followed = route.source) {
            is OutboundSource.Property -> "${followed.objectId}/${followed.property}"
            is OutboundSource.State -> "state of ${followed.machine}"
            is OutboundSource.Event -> "${followed.domain}/${followed.event}"
        }
        return "$source to ${route.to}"
    }
}
