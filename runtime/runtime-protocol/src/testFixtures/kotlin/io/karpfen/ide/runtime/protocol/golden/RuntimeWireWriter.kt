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
 * The one port of the runtime's JSON writers on the branch with IDE support, byte for byte. Everything the fake
 * runtime sends and every golden frame comes from here, so the IDE's tests read exactly what the runtime writes. Each
 * function names the runtime function it ports. The template goldens compare this port with the runtime's own
 * templates.
 *
 * Some JSON is written by hand. This is the envelope, the object and deletion payloads, the object JSON and the
 * snapshot. It quotes its strings with [quote], which follows the rule of the runtime's `ModelJsonWriter.quote`. That
 * rule is ported here on its own rather than borrowed from the client, so the tests can hold the client's reader and
 * quoting against it.
 *
 * The `stateChange`, `domainEvent` and `engineStatus` payloads are written by org.json in the version the runtime pins,
 * as the runtime builds them. So their member order is that of org.json. A `stateChange` payload has the order
 * `stack`, `modelElementId`, `revision`. A published event has the order `payload`, `source`, `ttlMs`, `event`,
 * `payloadFormat`, `timestamp`. An engine status has the order `halt`, `paused`, `waiting`, and a halt at a state the
 * order `timing`, `state`, `modelElementId`.
 *
 * The object holds no state.
 */
public object RuntimeWireWriter {

    /**
     * The push envelope `{"environmentKey":…,"clientId":…,"messageType":…,"payload":…}` with no whitespace, as the
     * runtime's `OutgoingMessage.toJson` writes it. The three strings are quoted and [payload] is spliced in as it is.
     */
    public fun envelope(environmentKey: String, clientId: String, messageType: String, payload: String): String =
        "{\"environmentKey\":${quote(environmentKey)},\"clientId\":${quote(clientId)},\"messageType\":${quote(messageType)}," +
            "\"payload\":$payload}"

    /**
     * The `objectChanged` payload `{"objectId":…,"revision":…,"value":…}` in this fixed member order, with the object's
     * JSON [value] spliced in. It ports the runtime's `ClientSessionManager.notifyObjectChange` with a revision.
     */
    public fun objectChangedPayload(objectId: String, revision: Long, value: String): String =
        "{\"objectId\":${quote(objectId)},\"revision\":$revision,\"value\":$value}"

    /**
     * The `objectDeleted` payload `{"objectId":…,"revision":…}`. It ports the runtime's
     * `ClientSessionManager.notifyObjectDeleted` with a revision.
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
     * The `stateMachineAttached` or `stateMachineDetached` payload with `modelElementId`, `stateMachine` and `revision`,
     * built with org.json as the runtime's `ObservationRelay.lifecyclePayload` builds it, in org.json's member order. A
     * null [stateMachine] is written as JSON `null`, as `JSONObject.NULL` is.
     */
    public fun stateMachinePayload(modelElementId: String, stateMachine: String?, revision: Long): String =
        JSONObject()
            .put("modelElementId", modelElementId)
            .put("stateMachine", stateMachine ?: JSONObject.NULL)
            .put("revision", revision)
            .toString()

    /**
     * The `domainEvent` payload `{"domain":…,"payload":…}`. The domain is quoted. The published event is an org.json
     * object with `event`, `payload`, `payloadFormat`, `source`, `timestamp` and `ttlMs` in org.json's member order. It
     * ports the runtime's `ObservationRelay.onPublished` and `ClientSessionManager.notifyDomainEvent`.
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
     * The `engineStatus` payload, built with org.json as the runtime's `TickByTickFeature.engineStatus` builds it, in
     * org.json's member order. It has [paused] and [waiting] and, if [halt] is not null, the halt, which is the JSON
     * that [stateHalt] or [transitionHalt] writes. The feature `Breakpoint` answers `getStatus` with the same text.
     */
    public fun engineStatusPayload(paused: Boolean, waiting: Boolean, halt: String? = null): String {
        val status = JSONObject()
        status.put("paused", paused)
        status.put("waiting", waiting)
        halt?.let { status.put("halt", JSONObject(it)) }
        return status.toString()
    }

    /**
     * The halt at the [timing], `entry` or `exit`, of the state [state] of the machine attached to [modelElementId],
     * built with org.json as the runtime's `BreakpointFeature.stateHalt` builds it.
     */
    public fun stateHalt(modelElementId: String, timing: String, state: String): String =
        JSONObject().put("modelElementId", modelElementId).put("timing", timing).put("state", state).toString()

    /**
     * The halt at the [timing], `start` or `end`, of a transition of the machine attached to [modelElementId], built
     * with org.json as the runtime's `BreakpointFeature.transitionHalt` builds it. [transition] is the identifier the
     * runtime writes for it, [kind] is `TRANSITION`, `SPLIT` or `JOIN`, and [index] its place in its file.
     */
    public fun transitionHalt(
        modelElementId: String,
        timing: String,
        transition: String,
        kind: String,
        from: List<String>,
        to: List<String>,
        index: Int,
    ): String =
        JSONObject()
            .put("modelElementId", modelElementId)
            .put("timing", timing)
            .put("transition", transition)
            .put("kind", kind)
            .put("from", from)
            .put("to", to)
            .put("index", index)
            .toString()

    /**
     * The JSON of [obj] as the runtime's `ModelJsonWriter.write` writes a `DataObject`. Entries are joined with `", "`,
     * and each key is followed by `": "`. The entries are `__id__` first, then `__type__`, then every property in
     * instance order, then every relation in instance order. A list property is written as `[…]` with `", "` between
     * its values. Relations of kind `has` and `knows` alike are written as full nested objects. An empty atomic
     * relation and an unresolved list entry are written as `null`. Ids, type names, keys and strings are quoted with
     * [quote]. Numbers are written by [number], and booleans as `true` and `false`.
     *
     * [known] resolves the [FakeTarget.Known] targets, which are the objects of the model by id. An id it does not
     * resolve is written as `null`. Objects shared without a loop are written once per reference.
     *
     * @throws IllegalStateException with the runtime's text `Reference loop through object '<id>' cannot be written as
     *   JSON` if an object is reachable from itself, naming the object met again.
     */
    public fun objectJson(obj: FakeObject, known: (String) -> FakeObject? = { null }): String =
        writeObject(obj, known, WritePath())

    /**
     * The model snapshot body as the runtime's `ModelSnapshot.toJson` writes it. Its shape is `{"environmentKey":…,
     * "phase":…,"revision":…,"activeStates":{…},"stateMachines":{…},"roots":[…]}` with no whitespace. The strings are
     * quoted with [quote], the stacks come in the order of [activeStates] and the instances in the order of
     * [stateMachines], a null definition name written as `null`. Every root's JSON from [roots] is spliced in as
     * written, separated by commas.
     */
    public fun snapshot(
        environmentKey: String,
        phase: RuntimePhase,
        revision: Long,
        activeStates: Map<String, List<String>>,
        roots: List<String>,
        stateMachines: Map<String, String?> = emptyMap(),
    ): String {
        val stacks = activeStates.entries.joinToString(",") { (machine, stack) ->
            "${quote(machine)}:[${stack.joinToString(",") { quote(it) }}]"
        }
        val machines = stateMachines.entries.joinToString(",") { (key, name) -> "${quote(key)}:${name?.let(::quote) ?: "null"}" }
        return "{\"environmentKey\":${quote(environmentKey)},\"phase\":${quote(phase.wireName)},\"revision\":$revision," +
            "\"activeStates\":{$stacks},\"stateMachines\":{$machines},\"roots\":[${roots.joinToString(",")}]}"
    }

    /**
     * [text] as a JSON string literal by the rule of the runtime's `ModelJsonWriter.quote`. The text is put in quotes.
     * Exactly `"`, `\` and U+0000 to U+001F are escaped. The escapes are `\b`, `\f`, `\n`, `\r` and `\t` where they
     * exist, and `\u00XX` with lower-case digits otherwise. Every other character stays as it is.
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
     * A number as the runtime writes a double. An integral number is written without fraction, so `5.0` becomes `5`,
     * `-0.0` becomes `0` and `1.0E18` becomes `1000000000000000000`. Any other number is written as `Double.toString`
     * writes it, for example `0.2`, `1.0E20`, `NaN`, `Infinity` and `-Infinity`.
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

    /** The JSON of a relation [target]. It is the nested or known object, or `null`. */
    private fun target(target: FakeTarget?, known: (String) -> FakeObject?, path: WritePath): String {
        val obj = when (target) {
            null -> null
            is FakeTarget.Nested -> target.value
            is FakeTarget.Known -> known(target.id)?.also { check(!path.holdsId(target.id)) { referenceLoopMessage(target.id) } }
        }
        return obj?.let { writeObject(it, known, path) } ?: "null"
    }

    /**
     * The objects on the path from the written root to the object being written. They are tracked by identity, as the
     * runtime tracks them, and by id for the targets of `knows` references. So a loop is found even when the objects
     * that the references resolve to are copies.
     */
    private class WritePath {
        /** The objects on the path, by identity. */
        private val objects: MutableMap<FakeObject, Int> = IdentityHashMap()

        /** How many objects on the path carry each id. */
        private val ids = HashMap<String, Int>()

        /** Puts [obj] on the path. Returns false if it is on the path already. */
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
