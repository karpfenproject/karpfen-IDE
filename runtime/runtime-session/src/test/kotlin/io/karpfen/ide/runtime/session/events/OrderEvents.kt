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
package io.karpfen.ide.runtime.session.events

import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.session.examples.DslTexts
import io.karpfen.ide.runtime.session.schema.TypeSchema

/**
 * A small event metamodel with a member of every kind, read from its texts as the analysis reads them. It has the
 * domain types `Point` and `Crate` and the events `order` and `ping`. `order` has a member of every kind, and `ping`
 * has no members.
 */
object OrderEvents {
    /** The domain metamodel's text. */
    const val DOMAIN_TEXT = """type "Point" "A point on the floor" {
    prop("x", "number")
    prop("y", "number")
}
type "Crate" "A crate" {
    prop("label", "string")
}
"""

    /** The events file's text. */
    const val EVENTS_TEXT = """type "order" "Places an order" {
    prop("count", "number")
    prop("urgent", "boolean")
    prop("note", "string")
    prop("tags", list("string"))
    has("destination", "Point")
    has("stops", list("Point"))
    knows("crate", "Crate")
}
type "ping" "Asks for an answer" {
}
"""

    /** The event metamodel, with the events file's types followed by the domain types. */
    val metamodel: Metamodel
        get() = DslTexts.metamodel(EVENTS_TEXT, DslTexts.metamodel(DOMAIN_TEXT).types)

    /** The schema of the event metamodel. */
    val schema: TypeSchema
        get() = TypeSchema.of(metamodel)

    /** The texts the event metamodel is read from. */
    val sources: EventSources
        get() = EventSources(TextSnapshot(DOMAIN_TEXT, 0), TextSnapshot(EVENTS_TEXT, 0))

    /** The event `order` in the domain `ops`, typed by the events file. */
    val order: InjectableEvent = event("order", "order", PayloadTypeOrigin.EVENT_TYPE)

    /** The event `ping` in the domain `ops`, typed by the events file, without members. */
    val ping: InjectableEvent = event("ping", "ping", PayloadTypeOrigin.EVENT_TYPE)

    /** The event `Point` in the domain `ops`, whose payload the runtime parses against the domain type. */
    val point: InjectableEvent = event("Point", "Point", PayloadTypeOrigin.DOMAIN_TYPE)

    /** The event `ghost` in the domain `ops`, whose payload the runtime never parses. */
    val ghost: InjectableEvent = event("ghost", null, null)

    /** The event [name] of the domain `ops` with the payload type [type] from [origin], listened to by nobody. */
    fun event(name: String, type: String?, origin: PayloadTypeOrigin?): InjectableEvent =
        InjectableEvent("ops", name, origin == PayloadTypeOrigin.EVENT_TYPE, type, origin, null, emptyList(), emptyList())
}
