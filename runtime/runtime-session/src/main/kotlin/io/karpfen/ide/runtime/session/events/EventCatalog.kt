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

import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.session.schema.TypeSchema
import java.nio.file.Path

/**
 * Where a state machine of the deployment listens to or raises an event.
 *
 * @property attachedTo the key of the object the machine runs attached to, or null for a definition, whose instances
 *   run on the objects the run gives them.
 * @property file the machine's file.
 * @property states the states whose transitions listen to the event, or whose actions raise it, each once, in file
 *   order.
 */
public data class EventRef(public val attachedTo: String?, public val file: Path, public val states: List<String>)

/** Which type of the event metamodel the runtime parses an event's payload against. */
public enum class PayloadTypeOrigin {
    /** A type of the events file named like the event. */
    EVENT_TYPE,

    /**
     * A domain type named like the event. The events file declares no type of that name. The runtime appends the
     * domain types to the event metamodel, so it parses the payload against that domain type.
     */
    DOMAIN_TYPE,
}

/**
 * An event that can be injected into a domain of the environment.
 *
 * @property domain the event's domain, verbatim. Matching is exact and case-sensitive.
 * @property name the event's name, verbatim. Names may contain spaces.
 * @property declared true if the events file declares a type of this name, which the user interface highlights.
 * @property payloadType the type the runtime parses a payload against, or null if it parses none.
 * @property payloadTypeOrigin where [payloadType] comes from, or null without one.
 * @property documentation the payload type's description, or null.
 * @property listeners the machines whose transitions wait for the event, in deployment order.
 * @property raisers the machines whose actions raise the event, in deployment order.
 */
public data class InjectableEvent(
    public val domain: String,
    public val name: String,
    public val declared: Boolean,
    public val payloadType: String?,
    public val payloadTypeOrigin: PayloadTypeOrigin?,
    public val documentation: String?,
    public val listeners: List<EventRef>,
    public val raisers: List<EventRef>,
)

/** Whether the events metamodel declares a domain, which the user interface highlights. */
public enum class DomainOrigin {
    /** At least one event of the domain is declared by the events file. */
    DECLARED,

    /** The state machines use the domain, but the events file declares none of its events. */
    USED_ONLY,
}

/**
 * An event domain of the deployment. The deployment registers a listener for it.
 *
 * @property name the domain's name.
 * @property origin whether the events file declares one of its events.
 * @property events its events.
 */
public data class EventDomain(public val name: String, public val origin: DomainOrigin, public val events: List<InjectableEvent>)

/**
 * The texts the runtime builds its event metamodel from, exactly as uploaded. These are the domain metamodel and the
 * events file. KModel payloads are checked against a metamodel read from these texts.
 *
 * @property metamodel the domain metamodel's text.
 * @property events the events file's text, or null if the deployment has none.
 */
public data class EventSources(public val metamodel: TextSnapshot, public val events: TextSnapshot?)

/**
 * The events of a deployment by domain, with the schema their payloads are checked against. Immutable.
 *
 * @property domains the deployment's domains in the plan's order.
 * @property schema the schema of the event metamodel. It holds the events file's types followed by the domain types.
 *   It is null if the deployment has no event definitions. In that case the runtime never parses payloads.
 * @property sources the texts the event metamodel is read from.
 */
public data class EventCatalog(
    public val domains: List<EventDomain>,
    public val schema: TypeSchema?,
    public val sources: EventSources,
) {
    /** Every event of every domain, in domain order. */
    public val events: List<InjectableEvent>
        get() = domains.flatMap { it.events }

    /** The domain [name], or null if the deployment has none of that name. */
    public fun domain(name: String): EventDomain? = domains.firstOrNull { it.name == name }

    /** The event [name] of the domain [domain], or null if the catalog has none. */
    public fun event(domain: String, name: String): InjectableEvent? = domain(domain)?.events?.firstOrNull { it.name == name }
}

/**
 * A state machine of the deployment as the catalog builder reads it, attached or a definition.
 *
 * @property attachedTo the key of the object it runs attached to, or null for a definition.
 * @property file its file.
 * @property machine the analyzed machine. It is read during the preparation only.
 */
public data class ListeningMachine(public val attachedTo: String?, public val file: Path, public val machine: StateMachine)
