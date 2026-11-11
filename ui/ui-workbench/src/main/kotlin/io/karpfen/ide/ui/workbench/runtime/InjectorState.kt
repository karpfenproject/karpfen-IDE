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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.session.events.DomainOrigin
import io.karpfen.ide.runtime.session.events.EventDomain
import io.karpfen.ide.runtime.session.events.EventInjector
import io.karpfen.ide.runtime.session.events.InjectableEvent
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.runtime.session.events.PayloadValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the event injector of a run shows. It lists the domains, the declared ones first and then those that only the
 * state machines use. It lists the events of the chosen domain, again the declared ones first. It shows the payload
 * text with its findings, whether events can be injected now, and the outcome of the last send.
 *
 * @property domains the domains of the deployment, declared ones first.
 * @property domain the chosen domain, or null for a deployment without domains.
 * @property events the events of [domain], declared ones first.
 * @property event the chosen event, or null.
 * @property kind how the payload is written: JSON for an event with a payload type, none otherwise.
 * @property text the payload as the user edits it.
 * @property validation the findings on the event and its payload.
 * @property availability whether events can be injected now.
 * @property sending true while a send is under way.
 * @property lastOutcome the outcome of the last send, or null before the first.
 */
public data class InjectorUiState(
    public val domains: List<EventDomain> = emptyList(),
    public val domain: String? = null,
    public val events: List<InjectableEvent> = emptyList(),
    public val event: InjectableEvent? = null,
    public val kind: PayloadKind = PayloadKind.NONE,
    public val text: String = "",
    public val validation: PayloadValidation = PayloadValidation.CLEAN,
    public val availability: InjectionAvailability = InjectionAvailability.NotYet,
    public val sending: Boolean = false,
    public val lastOutcome: InjectionOutcome? = null,
) {
    /**
     * True if the event can be sent now. That needs a chosen event, no send under way, a payload without errors and
     * injection that is live or queued.
     */
    public val canSend: Boolean
        get() = event != null && !sending && !validation.hasErrors &&
            (availability == InjectionAvailability.Live || availability == InjectionAvailability.Queued)
}

/**
 * The state of the event injector of one run. The user chooses a domain and an event, edits the payload, sees the
 * findings after every edit, and sends. The payload starts as the template of the event's payload type. Choosing
 * another event replaces the payload with that event's template. The findings are checked again when the availability
 * changes, because an environment that is not started yet queues events. The state is collected in [scope] on the UI
 * thread.
 *
 * @param injector the session's injector. It provides the catalog, the payload formats, the findings and the
 *   availability.
 * @param send sends a request through the runtime state.
 */
public class InjectorState internal constructor(
    private val injector: EventInjector,
    private val scope: CoroutineScope,
    private val send: suspend (InjectionRequest) -> InjectionOutcome,
) {
    private val ui = MutableStateFlow(initial())

    /** What the injector shows. */
    public val state: StateFlow<InjectorUiState> get() = ui.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            injector.availability.collect { availability -> ui.update { checked(it.copy(availability = availability)) } }
        }
    }

    /** Chooses the domain [name] and its first event. */
    public fun selectDomain(name: String) {
        ui.update { current -> withEvent(current.copy(domain = name, events = eventsOf(current.domains, name)), null) }
    }

    /** Chooses the event [name] of the chosen domain, with the template of its payload type. */
    public fun selectEvent(name: String) {
        ui.update { current -> withEvent(current, current.events.firstOrNull { it.name == name }) }
    }

    /** Replaces the payload with [text] and checks it. */
    public fun edit(text: String) {
        ui.update { checked(it.copy(text = text)) }
    }

    /**
     * Sends the chosen event with its payload and keeps the outcome. Does nothing if the event cannot be sent now. In
     * that case the returned job is already complete.
     */
    public fun send(): Job {
        val request = request(ui.value)
        if (request == null || !ui.value.canSend) return DONE
        ui.update { it.copy(sending = true) }
        return scope.launch {
            val outcome = send(request)
            ui.update { it.copy(sending = false, lastOutcome = outcome) }
        }
    }

    /** The state of the catalog's first domain and event. */
    private fun initial(): InjectorUiState {
        val domains = ordered(injector.catalog.domains)
        val domain = domains.firstOrNull()?.name
        val start = InjectorUiState(domains = domains, domain = domain, events = eventsOf(domains, domain))
        return withEvent(start.copy(availability = injector.availability.value), null)
    }

    /**
     * Returns [state] with [event] chosen, together with the event's template and findings. For null the first event
     * of the list is chosen.
     */
    private fun withEvent(state: InjectorUiState, event: InjectableEvent?): InjectorUiState {
        val chosen = event ?: state.events.firstOrNull()
        val kind = if (chosen?.payloadType != null) PayloadKind.JSON else PayloadKind.NONE
        val text = chosen?.let { injector.format(kind).template(it) }.orEmpty()
        return checked(state.copy(event = chosen, kind = kind, text = text))
    }

    /** Returns [state] with the findings on its request. */
    private fun checked(state: InjectorUiState): InjectorUiState =
        state.copy(validation = request(state)?.let(injector::validate) ?: PayloadValidation.CLEAN)

    /** The request of [state], or null if no event is chosen. */
    private fun request(state: InjectorUiState): InjectionRequest? =
        state.event?.let { InjectionRequest(it.domain, it.name, state.kind, state.text) }

    private companion object {
        /** The job of a send that did not happen. */
        val DONE: Job = Job().apply { complete() }

        /** Returns [domains] with the declared ones first. Each group keeps the catalog order. */
        fun ordered(domains: List<EventDomain>): List<EventDomain> =
            domains.filter { it.origin == DomainOrigin.DECLARED } + domains.filter { it.origin == DomainOrigin.USED_ONLY }

        /** The events of the domain [name] among [domains], declared ones first. */
        fun eventsOf(domains: List<EventDomain>, name: String?): List<InjectableEvent> {
            val events = domains.firstOrNull { it.name == name }?.events.orEmpty()
            return events.filter { it.declared } + events.filterNot { it.declared }
        }
    }
}
