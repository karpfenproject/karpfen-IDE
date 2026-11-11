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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.message.EventFrame
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory

/**
 * The [EventInjector] of a session. It checks requests with the event-level rules and with the payload format of the
 * request's kind. Then it sends them through the [sink].
 *
 * An event that the catalog does not list, such as a name typed by hand, is checked like a listed one. Its payload type
 * resolves by name in the event metamodel. A listed event of the same name in another domain serves as the model for
 * it. Without such an event, the type counts as a type of the events file. The reason is that the catalog keeps no
 * other record of which types the events file declares.
 *
 * @param catalog the events of the deployment.
 * @param attachedKeys the keys the deployment attaches machines to.
 * @param availability whether events can be injected now.
 * @param sink where frames go.
 * @param formats one payload format per kind.
 */
internal class DefaultEventInjector(
    override val catalog: EventCatalog,
    private val attachedKeys: Set<String>,
    override val availability: StateFlow<InjectionAvailability>,
    private val sink: EventSink,
    formats: List<PayloadFormat>,
) : EventInjector {
    /** The formats by kind. */
    private val formats: Map<PayloadKind, PayloadFormat> = formats.associateBy { it.kind }

    init {
        require(this.formats.keys == PayloadKind.entries.toSet()) { "one payload format per kind, not ${this.formats.keys}" }
    }

    /** The payload format of [kind]. */
    override fun format(kind: PayloadKind): PayloadFormat = formats.getValue(kind)

    /** The findings on [request] at the current availability. */
    override fun validate(request: InjectionRequest): PayloadValidation = validate(request, availability.value)

    /** Checks [request] again and sends it unless injection is unavailable or a finding is an error. */
    override suspend fun inject(request: InjectionRequest): InjectionOutcome {
        val now = availability.value
        if (now is InjectionAvailability.NotYet || now is InjectionAvailability.Unavailable) return InjectionOutcome.Unavailable(now)
        val validation = validate(request, now)
        if (validation.hasErrors) return InjectionOutcome.Refused(validation)
        val frame = EventFrame(request.domain, request.name, format(request.kind).encode(request.text))
        return when (val sent = sink.send(frame)) {
            is SendOutcome.Written -> InjectionOutcome.Sent(sent.frameText, validation)
            is SendOutcome.NotConnected -> unavailable("The connection to the runtime is not open (${describe(sent.state)}).")
            is SendOutcome.NotAllowed -> unavailable(sent.refusal.reason)
            is SendOutcome.Failed -> unavailable("The event could not be written to the socket: ${sent.failure}.")
        }
    }

    /** The findings on [request] when the availability is [now]. */
    private fun validate(request: InjectionRequest, now: InjectionAvailability): PayloadValidation {
        val found = ArrayList<Diagnostic>()
        val blank = request.domain.isBlank() || request.name.isBlank()
        if (blank) found += finding(PayloadDiagnosticCodes.EVENT_BLANK_NAME, "A blank domain or event name matches nothing.")
        val event = catalog.event(request.domain, request.name) ?: unlisted(request)
        if (!blank && event.listeners.isEmpty()) {
            found += finding(
                PayloadDiagnosticCodes.EVENT_NOT_LISTENED,
                "No attached machine listens to '${request.name}' in the domain '${request.domain}'; the event waits until its time to live expires.",
            )
        }
        if (request.domain in attachedKeys) {
            found += finding(
                PayloadDiagnosticCodes.EVENT_DOMAIN_IS_ATTACHMENT,
                "The domain '${request.domain}' is named like an attached object: forcing that machine's state discards the event.",
            )
        }
        if (now is InjectionAvailability.Queued) {
            found += finding(
                PayloadDiagnosticCodes.EVENT_QUEUED_UNTIL_START,
                "The environment is not started yet: the event waits, and its time to live runs from now.",
            )
        }
        return PayloadValidation(found) + format(request.kind).validate(request.text, event)
    }

    /** The event of [request], which the catalog does not list. */
    private fun unlisted(request: InjectionRequest): InjectableEvent {
        val namesake = catalog.events.firstOrNull { it.name == request.name }
        val type = namesake?.payloadType ?: catalog.schema?.type(request.name)?.name
        return InjectableEvent(
            domain = request.domain,
            name = request.name,
            declared = namesake?.declared ?: false,
            payloadType = type,
            payloadTypeOrigin = namesake?.payloadTypeOrigin ?: type?.let { PayloadTypeOrigin.EVENT_TYPE },
            documentation = namesake?.documentation,
            listeners = emptyList(),
            raisers = emptyList(),
        )
    }

    /** The outcome of a send that did not reach the socket, logged. */
    private fun unavailable(reason: String): InjectionOutcome {
        logger.info("An event was not injected: {}", reason)
        return InjectionOutcome.Unavailable(InjectionAvailability.Unavailable(reason))
    }

    /** The event-level finding [code] with [message], at the start of the payload. */
    private fun finding(code: String, message: String): Diagnostic = PayloadDiagnosticCodes.finding(code, TextRange.EMPTY, message)

    /** [state] in a few words. */
    private fun describe(state: ConnectionState): String =
        when (state) {
            ConnectionState.Idle -> "not opened yet"
            is ConnectionState.Connecting -> "connecting"
            is ConnectionState.Open -> "open"
            is ConnectionState.Reconnecting -> "reconnecting"
            is ConnectionState.Ended -> "ended"
        }

    private companion object {
        /** The injector's logger. */
        private val logger = LoggerFactory.getLogger(DefaultEventInjector::class.java)
    }
}
