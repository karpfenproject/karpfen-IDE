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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.runtime.protocol.connection.SendOutcome
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import kotlinx.coroutines.flow.StateFlow

/** Whether events can be injected now. */
public sealed interface InjectionAvailability {
    /** The environment is still being deployed. */
    public data object NotYet : InjectionAvailability

    /** The environment is activated but not started: an injected event waits, and its time to live runs from receipt. */
    public data object Queued : InjectionAvailability

    /** The environment runs and the connection is open. */
    public data object Live : InjectionAvailability

    /**
     * Events cannot be injected.
     *
     * @property reason why, in a plain sentence.
     */
    public data class Unavailable(public val reason: String) : InjectionAvailability
}

/**
 * An event to inject.
 *
 * @property domain the event's domain.
 * @property name the event's name.
 * @property kind how the payload is written.
 * @property text the payload; ignored for [PayloadKind.NONE].
 */
public data class InjectionRequest(public val domain: String, public val name: String, public val kind: PayloadKind, public val text: String)

/** What became of an injection. */
public sealed interface InjectionOutcome {
    /**
     * The event frame was written to the socket.
     *
     * @property frameText the frame as written.
     * @property validation the findings, none of them an error.
     */
    public data class Sent(public val frameText: String, public val validation: PayloadValidation) : InjectionOutcome

    /**
     * The event was not sent, because a finding is an error.
     *
     * @property validation the findings.
     */
    public data class Refused(public val validation: PayloadValidation) : InjectionOutcome

    /**
     * The event was not sent, because events cannot be injected now or the connection could not take it.
     *
     * @property availability why.
     */
    public data class Unavailable(public val availability: InjectionAvailability) : InjectionOutcome
}

/** Where injected event frames go; the session's sink routes them through its command order, so no event overtakes a stop. */
public fun interface EventSink {
    /** Sends [frame]. */
    public suspend fun send(frame: EventFrame): SendOutcome
}

/**
 * Injects events into a running environment: offers the catalog and a payload format per kind, checks requests as the
 * runtime will read them, and sends what passes.
 */
public interface EventInjector {
    /** The events of the deployment by domain. */
    public val catalog: EventCatalog

    /** Whether events can be injected now. */
    public val availability: StateFlow<InjectionAvailability>

    /** The payload format of [kind]. */
    public fun format(kind: PayloadKind): PayloadFormat

    /**
     * The findings on [request]: a blank domain or name, an event no attached machine listens to, a domain named like an
     * attached key, an environment that only queues events, and the payload format's findings on the text. Ranges of
     * the findings on the event itself are empty, at the start of the payload.
     */
    public fun validate(request: InjectionRequest): PayloadValidation

    /**
     * Checks [request] again and sends it unless events cannot be injected now or a finding is an error. [InjectionOutcome.Sent]
     * means the frame was written to the socket; the runtime never answers events.
     */
    public suspend fun inject(request: InjectionRequest): InjectionOutcome
}

/**
 * The injector of [catalog] for an environment whose machines are attached to [attachedKeys], with the session's
 * [availability] and [sink]. KModel payloads are checked with core-document's KModel parser and the dsl-tools reader
 * adapter.
 */
public fun EventInjector(
    catalog: EventCatalog,
    attachedKeys: Set<String>,
    availability: StateFlow<InjectionAvailability>,
    sink: EventSink,
): EventInjector {
    val formats = listOf(
        NoPayloadFormat,
        JsonPayloadFormat(catalog.schema),
        KmodelPayloadFormat(
            sources = catalog.sources,
            schema = catalog.schema,
            parser = defaultLanguageRegistry().support(KarpfenLanguage.KMODEL).parser,
            reader = DslReader(),
        ),
    )
    return DefaultEventInjector(catalog, attachedKeys.toSet(), availability, sink, formats)
}

/** No payload: the frame says so explicitly, and any text is dropped. Stateless. */
internal object NoPayloadFormat : PayloadFormat {
    /** The kind this format writes. */
    override val kind: PayloadKind = PayloadKind.NONE

    /** No text. */
    override fun template(event: InjectableEvent): String = ""

    /** No findings: nothing is sent to check. */
    override fun validate(text: String, event: InjectableEvent): PayloadValidation = PayloadValidation.CLEAN

    /** No payload, whatever [text] is. */
    override fun encode(text: String): EventPayload = EventPayload.None
}
