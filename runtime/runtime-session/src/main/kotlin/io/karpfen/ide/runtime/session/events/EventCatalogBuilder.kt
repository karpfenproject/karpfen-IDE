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
import io.karpfen.dsl.states.JoinTransition
import io.karpfen.dsl.states.SplitTransition
import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.Transition
import io.karpfen.dsl.states.TransitionLike
import io.karpfen.dsl.states.actions.ActionBlock
import io.karpfen.dsl.states.actions.ActionOperationType
import io.karpfen.dsl.states.actions.ActionRule
import io.karpfen.dsl.states.actions.InScopeBlock
import io.karpfen.dsl.states.actions.ValueActionRightSide
import io.karpfen.dsl.states.actions.WithBlock
import io.karpfen.dsl.states.conditions.CompositeCondition
import io.karpfen.dsl.states.conditions.Condition
import io.karpfen.dsl.states.conditions.EventCondition
import io.karpfen.ide.runtime.session.schema.TypeSchema
import java.nio.file.Path

/**
 * Builds the [EventCatalog] of a deployment from its analyzed state machines during the preparation.
 *
 * The domains are the plan's domains, in that order, because the deployment registers a listener for each. The events
 * metamodel declares event types, not domains, so a domain's origin is derived from its events. An event is declared
 * when the events file declares a type of its name. A domain is [DomainOrigin.DECLARED] when at least one of the events
 * its machines use is declared. Otherwise it is [DomainOrigin.USED_ONLY].
 *
 * Listened events come from every transition, split and join. The condition's clauses are flattened recursively in
 * order, and the first event condition is taken, because it is the only one the runtime evaluates. Domain and name are
 * kept verbatim. The listening states are the transition's source states.
 *
 * Raised events come from every `EVENT` action in the entry and do blocks of every state. This includes inner states
 * and the bodies of scope and macro blocks. The left side is the domain, and a literal right side is the name. A macro
 * or `EVAL` right side names its event only at run time, so it contributes nothing beyond its domain. Raised events are
 * injectable too.
 *
 * Declared events that no machine names are the events file's types that no `EVENT` call names. They are listed in
 * every declared domain, in the events file's order. That way they can be injected into the domains the events file
 * speaks for. They do not change a domain's origin.
 *
 * Payload types resolve as the runtime resolves them. The first choice is the events file's type of the event's name,
 * which is [PayloadTypeOrigin.EVENT_TYPE]. The second is a domain type of that name, which is
 * [PayloadTypeOrigin.DOMAIN_TYPE]. Otherwise there is none. Without an events metamodel the runtime parses no payload
 * at all.
 *
 * Every event found belongs to one of the plan's domains, because both come from the same `EVENT` calls. An event of
 * another domain is left out. Within a domain, the events come in the order the machines first name them.
 *
 * Stateless. The dsl-tools objects it reads stay in the call.
 */
public object EventCatalogBuilder {
    /**
     * Builds the catalog of [domains] from the events that [machines] listen to and raise. [eventsMetamodel] gives the
     * payload types. It holds the events file's types followed by the domain types, and it is null without event
     * definitions. [eventTypeNames] are the type names that the events file declares itself. [sources] are the texts
     * of the event metamodel.
     */
    public fun build(
        domains: List<String>,
        machines: List<ListeningMachine>,
        eventsMetamodel: Metamodel?,
        eventTypeNames: List<String>,
        sources: EventSources,
    ): EventCatalog {
        val schema = eventsMetamodel?.let(TypeSchema::of)
        val uses = EventUses()
        machines.forEach { uses.scan(it) }
        val declaredNames = eventTypeNames.toSet()
        val named = uses.names()
        val unnamedDeclared = eventTypeNames.distinct().filter { it !in named }
        val catalogDomains = domains.distinct().map { domain ->
            val used = uses.eventsOf(domain).map { (name, refs) -> event(domain, name, refs, declaredNames, schema) }
            val origin = if (used.any { it.declared }) DomainOrigin.DECLARED else DomainOrigin.USED_ONLY
            val extra = if (origin == DomainOrigin.DECLARED) {
                unnamedDeclared.map { event(domain, it, EventRefs(), declaredNames, schema) }
            } else {
                emptyList()
            }
            EventDomain(domain, origin, used + extra)
        }
        return EventCatalog(catalogDomains, schema, sources)
    }

    /** The injectable event [name] of [domain] with its [refs]. */
    private fun event(domain: String, name: String, refs: EventRefs, declaredNames: Set<String>, schema: TypeSchema?): InjectableEvent {
        val type = schema?.type(name)
        val origin = when {
            type == null -> null
            name in declaredNames -> PayloadTypeOrigin.EVENT_TYPE
            else -> PayloadTypeOrigin.DOMAIN_TYPE
        }
        return InjectableEvent(
            domain = domain,
            name = name,
            declared = name in declaredNames,
            payloadType = type?.name,
            payloadTypeOrigin = origin,
            documentation = type?.comment,
            listeners = refs.listeners.refs(),
            raisers = refs.raisers.refs(),
        )
    }

    /** The listeners and raisers of one event, as they are collected. */
    private class EventRefs {
        /** The listening states by machine. */
        val listeners = StatesByMachine()

        /** The raising states by machine. */
        val raisers = StatesByMachine()
    }

    /** States collected per machine, each machine and state once, in the order they were met. */
    private class StatesByMachine {
        /** The states by machine. A machine is keyed by its attachment key, null for a definition, and its file. */
        private val states = LinkedHashMap<Pair<String?, Path>, LinkedHashSet<String>>()

        /** Adds [stateNames] of [machine]. */
        fun add(machine: ListeningMachine, stateNames: List<String>) {
            states.getOrPut(machine.attachedTo to machine.file) { LinkedHashSet() } += stateNames
        }

        /** The collected references. */
        fun refs(): List<EventRef> = states.map { (machine, names) -> EventRef(machine.first, machine.second, names.toList()) }
    }

    /** The events the machines use, by domain and name in the order they were met. */
    private class EventUses {
        /** The references by domain, then by name. */
        private val byDomain = LinkedHashMap<String, LinkedHashMap<String, EventRefs>>()

        /** Every event name any machine names, in any domain. */
        fun names(): Set<String> = byDomain.values.flatMapTo(HashSet()) { it.keys }

        /** The events of [domain] with their references. */
        fun eventsOf(domain: String): List<Pair<String, EventRefs>> = byDomain[domain].orEmpty().map { it.key to it.value }

        /** Collects the listened and raised events of [listening]. */
        fun scan(listening: ListeningMachine) {
            listening.machine.transitions.forEach { transition ->
                val condition = firstEventCondition(transition.condition) ?: return@forEach
                refsOf(condition.eventDomain, condition.eventValue).listeners.add(listening, sourceStates(transition))
            }
            listening.machine.states.forEach { raised(listening, it) }
        }

        /** Collects the events [state] and its inner states raise. */
        private fun raised(listening: ListeningMachine, state: State) {
            raisedIn(listening, state.name, state.onEntry)
            raisedIn(listening, state.name, state.onDo)
            state.innerStates.forEach { raised(listening, it) }
        }

        /** Collects the events the actions of [block] of the state [stateName] raise, nested blocks included. */
        private fun raisedIn(listening: ListeningMachine, stateName: String, block: ActionBlock) {
            block.items.forEach { item ->
                when (item) {
                    is ActionRule -> {
                        val name = (item.rightSide as? ValueActionRightSide)?.value
                        if (item.operationType == ActionOperationType.EVENT && name != null) {
                            refsOf(item.leftSide, name).raisers.add(listening, listOf(stateName))
                        }
                    }
                    is InScopeBlock -> raisedIn(listening, stateName, item.body)
                    is WithBlock -> raisedIn(listening, stateName, item.body)
                }
            }
        }

        /** The references of the event [name] of [domain], created on first use. */
        private fun refsOf(domain: String, name: String): EventRefs =
            byDomain.getOrPut(domain) { LinkedHashMap() }.getOrPut(name) { EventRefs() }
    }

    /** The first event condition among the recursively flattened clauses of [condition], or null if it has none. */
    private fun firstEventCondition(condition: Condition): EventCondition? =
        when (condition) {
            is EventCondition -> condition
            is CompositeCondition -> condition.clauses.firstNotNullOfOrNull(::firstEventCondition)
            else -> null
        }

    /** The states a transition leaves. */
    private fun sourceStates(transition: TransitionLike): List<String> =
        when (transition) {
            is Transition -> listOf(transition.fromState)
            is SplitTransition -> listOf(transition.fromState)
            is JoinTransition -> transition.fromStates
        }
}
