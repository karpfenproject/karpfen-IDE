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
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.runtime.protocol.message.EventPayload

/** How an event's payload is written. */
public enum class PayloadKind {
    /** No payload. */
    NONE,

    /** A JSON object, parsed against the payload type. */
    JSON,

    /** A KModel snippet, whose first root object becomes the payload. */
    KMODEL,
}

/**
 * The findings on an event to inject and its payload, ranges relative to the payload text.
 *
 * @property diagnostics the findings in order.
 */
public data class PayloadValidation(public val diagnostics: List<Diagnostic>) {
    /** True if a finding is an error: the event is not sent. */
    public val hasErrors: Boolean
        get() = diagnostics.any { it.severity == Severity.ERROR }

    /** The codes of the findings in order, for tests and logs. */
    public val codes: List<String>
        get() = diagnostics.map { it.code }

    /** This validation followed by the findings of [other]. */
    public operator fun plus(other: PayloadValidation): PayloadValidation = PayloadValidation(diagnostics + other.diagnostics)

    public companion object {
        /** No findings. */
        public val CLEAN: PayloadValidation = PayloadValidation(emptyList())
    }
}

/**
 * One way of writing a payload, a strategy chosen by [PayloadKind]: it offers a template, checks a text as the runtime
 * would read it, and encodes it for the event frame.
 */
public interface PayloadFormat {
    /** The kind this format writes. */
    public val kind: PayloadKind

    /** A text to start from for [event]: its payload type with every member at the value the runtime would default to. */
    public fun template(event: InjectableEvent): String

    /** The findings on [text] as the payload of [event]. */
    public fun validate(text: String, event: InjectableEvent): PayloadValidation

    /** [text] as the payload of an event frame. */
    public fun encode(text: String): EventPayload
}

/**
 * The codes of the findings on events and payloads, with their severities. The payload checks mirror the runtime's
 * `JsonToModelConverter` and `KmodelDSLConverter.parseSingleObject`, which turn a payload the runtime cannot read into
 * a null payload without telling anyone; the event still fires by name.
 */
public object PayloadDiagnosticCodes {
    /** The payload is not JSON. */
    public const val JSON_SYNTAX: String = "payload-json-syntax"

    /** The payload is no JSON object; the converter refuses anything else and the payload becomes null. */
    public const val NOT_OBJECT: String = "payload-not-object"

    /** A key is repeated; org.json refuses repeated keys and the payload becomes null, silently. */
    public const val DUPLICATE_KEY: String = "payload-duplicate-key"

    /** `__id__` is no string; it is read as a string, so another value makes the payload null. */
    public const val ID_NOT_STRING: String = "payload-id-not-string"

    /** A string `__id__` becomes the payload object's id. */
    public const val ID_ASSIGNED: String = "payload-id-assigned"

    /** A number property is given as something else than a JSON number, which makes the whole payload null. */
    public const val NUMBER_EXPECTED: String = "payload-number-expected"

    /** A boolean property is given as something else than a JSON boolean, which makes the whole payload null. */
    public const val BOOLEAN_EXPECTED: String = "payload-boolean-expected"

    /** A list property is not given as an array. */
    public const val LIST_EXPECTED: String = "payload-list-expected"

    /** A relation is not given as an object, or a list relation not as an array of objects. */
    public const val OBJECT_EXPECTED: String = "payload-object-expected"

    /** A key the payload type does not declare; the runtime ignores it, so it is likely a typo. */
    public const val UNKNOWN_KEY: String = "payload-unknown-key"

    /** A `knows` member: the runtime builds a new object and never refers to an existing one. */
    public const val KNOWS_CREATES_OBJECT: String = "payload-knows-creates-object"

    /** No event type of the event's name: the runtime parses the payload against the domain type of that name. */
    public const val DOMAIN_TYPE: String = "payload-domain-type"

    /** No event or domain type of the event's name: the runtime never parses the payload. */
    public const val UNTYPED_EVENT: String = "payload-untyped-event"

    /** The environment has no event definitions: payloads are never parsed. */
    public const val NOT_PARSED: String = "payload-not-parsed"

    /** A member of the payload type is absent; the runtime fills in `0`, `false`, `""`, an empty list or no object. */
    public const val MEMBER_DEFAULTED: String = "payload-member-defaulted"

    /** A member is `null`; the runtime leaves it out. */
    public const val MEMBER_OMITTED: String = "payload-member-omitted"

    /** A string property is given another value; the runtime takes its text. */
    public const val TEXT_FROM_VALUE: String = "payload-text-from-value"

    /** The KModel snippet has syntax or reader errors; their own findings are reported next to this one. */
    public const val KMODEL_INVALID: String = "payload-kmodel-invalid"

    /** The KModel snippet declares no object, so the runtime finds none. */
    public const val KMODEL_NO_OBJECT: String = "payload-kmodel-no-object"

    /** The KModel snippet declares several root objects; the runtime takes the first. */
    public const val KMODEL_SEVERAL_OBJECTS: String = "payload-kmodel-several-objects"

    /** The root object's type differs from the event's name; the runtime uses the snippet's type. */
    public const val KMODEL_TYPE: String = "payload-kmodel-type"

    /** The domain or the name is blank, which matches nothing. */
    public const val EVENT_BLANK_NAME: String = "event-blank-name"

    /** No attached machine listens to the domain and name; the event waits until its time to live expires. */
    public const val EVENT_NOT_LISTENED: String = "event-not-listened"

    /** The environment is activated but not started: the event waits, and its time to live runs from receipt. */
    public const val EVENT_QUEUED_UNTIL_START: String = "event-queued-until-start"

    /** The domain is named like an attached object key: forcing that machine's state discards the event. */
    public const val EVENT_DOMAIN_IS_ATTACHMENT: String = "event-domain-is-attachment"

    /** Every code with its severity. */
    public val SEVERITIES: Map<String, Severity> = linkedMapOf(
        JSON_SYNTAX to Severity.ERROR,
        NOT_OBJECT to Severity.ERROR,
        DUPLICATE_KEY to Severity.ERROR,
        ID_NOT_STRING to Severity.ERROR,
        ID_ASSIGNED to Severity.INFO,
        NUMBER_EXPECTED to Severity.ERROR,
        BOOLEAN_EXPECTED to Severity.ERROR,
        LIST_EXPECTED to Severity.ERROR,
        OBJECT_EXPECTED to Severity.ERROR,
        UNKNOWN_KEY to Severity.WARNING,
        KNOWS_CREATES_OBJECT to Severity.WARNING,
        DOMAIN_TYPE to Severity.INFO,
        UNTYPED_EVENT to Severity.WARNING,
        NOT_PARSED to Severity.WARNING,
        MEMBER_DEFAULTED to Severity.INFO,
        MEMBER_OMITTED to Severity.INFO,
        TEXT_FROM_VALUE to Severity.INFO,
        KMODEL_INVALID to Severity.ERROR,
        KMODEL_NO_OBJECT to Severity.ERROR,
        KMODEL_SEVERAL_OBJECTS to Severity.WARNING,
        KMODEL_TYPE to Severity.WARNING,
        EVENT_BLANK_NAME to Severity.ERROR,
        EVENT_NOT_LISTENED to Severity.WARNING,
        EVENT_QUEUED_UNTIL_START to Severity.WARNING,
        EVENT_DOMAIN_IS_ATTACHMENT to Severity.WARNING,
    )

    /**
     * The finding [code] at [range] with [message], with the code's severity.
     *
     * @throws IllegalArgumentException for a code this object does not declare.
     */
    public fun finding(code: String, range: TextRange, message: String): Diagnostic {
        val severity = requireNotNull(SEVERITIES[code]) { "unknown payload finding code '$code'" }
        return Diagnostic(range, severity, message, code, DiagnosticSource.IDE)
    }
}
