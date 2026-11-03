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
package io.karpfen.ide.runtime.protocol.golden

import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/**
 * The one port of the runtime's JSON writers on the branch with IDE support, byte for byte: everything the fake runtime
 * sends and every golden frame comes from here, so the IDE's tests read exactly what the runtime writes. Each function
 * names the runtime function it ports; the template goldens compare this port with the runtime's own templates.
 *
 * Hand-written JSON (the envelope, the object and deletion payloads, the object JSON and the snapshot) quotes its
 * strings with [quote], the rule of the runtime's `ModelJsonWriter.quote`, which is ported here on its own rather than
 * borrowed from the client, so the tests can hold the client's reader and quoting against it. The `stateChange` and
 * `domainEvent` payloads are written by org.json in the version the runtime pins, as the runtime builds them, so their
 * member order is that of org.json (`{"stack":…,"modelElementId":…,"revision":…}`; the published event's members in
 * the order `payload`, `source`, `ttlMs`, `event`, `payloadFormat`, `timestamp`).
 *
 * Stateless.
 */
public object RuntimeWireWriter {

    /**
     * The push envelope `{"environmentKey":…,"clientId":…,"messageType":…,"payload":…}` with no whitespace, the three
     * strings quoted and [payload] spliced in as it is (the runtime's `OutgoingMessage.toJson`).
     */
    public fun envelope(environmentKey: String, clientId: String, messageType: String, payload: String): String =
        "{\"environmentKey\":${quote(environmentKey)},\"clientId\":${quote(clientId)},\"messageType\":${quote(messageType)}," +
            "\"payload\":$payload}"

    /**
     * The `objectChanged` payload `{"objectId":…,"revision":…,"value":…}` in this fixed member order, with the object's
     * JSON [value] spliced in (the runtime's `ClientSessionManager.notifyObjectChange` with a revision).
     */
    public fun objectChangedPayload(objectId: String, revision: Long, value: String): String =
        "{\"objectId\":${quote(objectId)},\"revision\":$revision,\"value\":$value}"

    /**
     * The `objectDeleted` payload `{"objectId":…,"revision":…}` (the runtime's `ClientSessionManager.notifyObjectDeleted`
     * with a revision).
     */
    public fun objectDeletedPayload(objectId: String, revision: Long): String =
        "{\"objectId\":${quote(objectId)},\"revision\":$revision}"

    /**
     * The `stateChange` payload with `modelElementId`, `stack` and `revision`, built with org.json as the runtime's
     * `ObservationRelay.onActiveStackChanged` builds it, in org.json's member order.
     */
    public fun stateChangePayload(modelElementId: String, stack: List<String>, revision: Long): String =
        JSONObject()
            .put("modelElementId", modelElementId)
            .put("stack", JSONArray(stack))
            .put("revision", revision)
            .toString()

    /**
     * The `domainEvent` payload `{"domain":…,"payload":…}`: the domain quoted, the published event an org.json object
     * with `event`, `payload`, `payloadFormat`, `source`, `timestamp` and `ttlMs` in org.json's member order (the
     * runtime's `ObservationRelay.onPublished` and `ClientSessionManager.notifyDomainEvent`).
     */
    public fun domainEventPayload(
        domain: String,
        event: String,
        payload: String,
        payloadFormat: String,
        source: String,
        timestamp: Long,
        ttlMs: Long,
    ): String {
        val published = JSONObject()
            .put("event", event)
            .put("payload", payload)
            .put("payloadFormat", payloadFormat)
            .put("source", source)
            .put("timestamp", timestamp)
            .put("ttlMs", ttlMs)
            .toString()
        return "{\"domain\":${quote(domain)},\"payload\":$published}"
    }

    /**
     * The JSON of [obj] as the runtime's `ModelJsonWriter.write` writes a `DataObject`: entries joined with `", "`, each
     * key followed by `": "`; first `__id__`, then `__type__`, then every property in instance order (a list as `[…]`
     * with `", "` between its values), then every relation in instance order, `has` and `knows` alike as full nested
     * objects, an empty atomic relation and an unresolved list entry as `null`. Ids, type names, keys and strings are
     * quoted with [quote]; numbers are written by [number]; booleans as `true` and `false`.
     *
     * [known] resolves the [FakeTarget.Known] targets, the objects of the model by id; an id it does not resolve is
     * written as `null`. Objects shared without a loop are written once per reference.
     *
     * @throws IllegalStateException with the runtime's text `Reference loop through object '<id>' cannot be written as
     *   JSON` if an object is reachable from itself, naming the object met again.
     */
    public fun objectJson(obj: FakeObject, known: (String) -> FakeObject? = { null }): String =
        writeObject(obj, known, WritePath())

    /**
     * The model snapshot body as the runtime's `ModelSnapshot.toJson` writes it: `{"environmentKey":…,"phase":…,
     * "revision":…,"activeStates":{…},"roots":[…]}` with no whitespace, the strings quoted with [quote], the stacks in
     * the order of [activeStates] and every root's JSON from [roots] spliced in as written, separated by commas.
     */
    public fun snapshot(
        environmentKey: String,
        phase: RuntimePhase,
        revision: Long,
        activeStates: Map<String, List<String>>,
        roots: List<String>,
    ): String {
        val stacks = activeStates.entries.joinToString(",") { (machine, stack) ->
            "${quote(machine)}:[${stack.joinToString(",") { quote(it) }}]"
        }
        return "{\"environmentKey\":${quote(environmentKey)},\"phase\":${quote(phase.wireName)},\"revision\":$revision," +
            "\"activeStates\":{$stacks},\"roots\":[${roots.joinToString(",")}]}"
    }

    /**
     * [text] as a JSON string literal by the runtime's `ModelJsonWriter.quote`: in quotes, with exactly `"`, `\` and
     * U+0000 to U+001F escaped (`\b`, `\f`, `\n`, `\r`, `\t`, otherwise `\u00XX` with lower-case digits) and every other
     * character as it is.
     */
    public fun quote(text: String): String {
        val quoted = StringBuilder("\"")
        text.forEach { char ->
            quoted.append(
                when {
                    char == '"' -> "\\\""
                    char == '\\' -> "\\\\"
                    char == '\b' -> "\\b"
                    char == '\u000C' -> "\\f"
                    char == '\n' -> "\\n"
                    char == '\r' -> "\\r"
                    char == '\t' -> "\\t"
                    char.code < CONTROL_LIMIT -> "\\u" + char.code.toString(HEX_RADIX).padStart(ESCAPE_DIGITS, '0')
                    else -> char.toString()
                },
            )
        }
        return quoted.append('"').toString()
    }

    /**
     * A number as the runtime writes a double: without fraction when it is integral (`5.0` as `5`, `-0.0` as `0`,
     * `1.0E18` as `1000000000000000000`), otherwise as `Double.toString` writes it (`0.2`, `1.0E20`, `NaN`, `Infinity`,
     * `-Infinity`).
     */
    public fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** The text with which the runtime refuses to write an object reachable from itself through [id]. */
    public fun referenceLoopMessage(id: String): String = "Reference loop through object '$id' cannot be written as JSON"

    /** The JSON of [obj], whose ancestors on the current path are [path]. */
    private fun writeObject(obj: FakeObject, known: (String) -> FakeObject?, path: WritePath): String {
        check(path.enter(obj)) { referenceLoopMessage(obj.id) }
        val entries = ArrayList<String>()
        entries += "${quote("__id__")}: ${quote(obj.id)}"
        entries += "${quote("__type__")}: ${quote(obj.typeName)}"
        obj.properties.forEach { property ->
            val value = when (property) {
                is FakeProperty.One -> scalar(property.value)
                is FakeProperty.Many -> property.values.joinToString(", ", "[", "]") { scalar(it) }
            }
            entries += "${quote(property.key)}: $value"
        }
        obj.relations.forEach { relation ->
            val value = when (relation) {
                is FakeRelation.One -> target(relation.target, known, path)
                is FakeRelation.Many -> relation.targets.joinToString(", ", "[", "]") { target(it, known, path) }
            }
            entries += "${quote(relation.key)}: $value"
        }
        path.leave(obj)
        return entries.joinToString(", ", "{", "}")
    }

    /** The JSON of a relation [target]: the nested or known object, or `null`. */
    private fun target(target: FakeTarget?, known: (String) -> FakeObject?, path: WritePath): String {
        val obj = when (target) {
            null -> null
            is FakeTarget.Nested -> target.value
            is FakeTarget.Known -> known(target.id)?.also { check(!path.holdsId(target.id)) { referenceLoopMessage(target.id) } }
        }
        return obj?.let { writeObject(it, known, path) } ?: "null"
    }

    /**
     * The objects on the path from the written root to the object being written, by identity as the runtime tracks
     * them, and by id for the targets of `knows` references, so that a loop is found even when the objects the
     * references resolve to are copies.
     */
    private class WritePath {
        private val objects: MutableMap<FakeObject, Int> = IdentityHashMap()
        private val ids = HashMap<String, Int>()

        /** Puts [obj] on the path; false if it is on it already. */
        fun enter(obj: FakeObject): Boolean {
            if (objects.containsKey(obj)) return false
            objects[obj] = 1
            ids.merge(obj.id, 1, Int::plus)
            return true
        }

        /** Takes [obj] off the path. */
        fun leave(obj: FakeObject) {
            objects.remove(obj)
            ids.computeIfPresent(obj.id) { _, count -> if (count > 1) count - 1 else null }
        }

        /** True if an object with the non-empty [id] is on the path. */
        fun holdsId(id: String): Boolean = id.isNotEmpty() && id in ids
    }

    /** The JSON of a property value. */
    private fun scalar(value: FakeScalar): String =
        when (value) {
            is FakeScalar.Number -> number(value.value)
            is FakeScalar.Text -> quote(value.value)
            is FakeScalar.Flag -> if (value.value) "true" else "false"
        }

    /** The first character that needs no escape. */
    private const val CONTROL_LIMIT = 0x20

    /** The radix of a `\u00XX` escape. */
    private const val HEX_RADIX = 16

    /** The digits of a `\u00XX` escape. */
    private const val ESCAPE_DIGITS = 4
}
