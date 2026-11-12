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

import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.FlowPolicy
import io.karpfen.ide.bridges.api.InboundRoute
import io.karpfen.ide.bridges.api.JsonPath
import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.bridges.api.OutboundRoute
import io.karpfen.ide.bridges.api.OutboundSource
import io.karpfen.ide.bridges.api.PayloadField
import io.karpfen.ide.bridges.api.ValueSource
import io.karpfen.ide.runtime.protocol.message.WireField
import io.karpfen.ide.runtime.protocol.message.WireValue

/**
 * What [BridgeConfigFile.read] made of a bridge file.
 *
 * @property config the configuration, or null if the file is no JSON object or names no known kind of bridge.
 * @property problems what is wrong with the file, each in one sentence that names the place. A route with a problem
 *   is left out of [config], and the other routes stay.
 */
public data class BridgeConfigReading(public val config: BridgeConfig?, public val problems: List<String>)

/**
 * The text of a bridge file, `*.kbridge.json`. The file is JSON:
 *
 * ```
 * {
 *   "kind": "http",
 *   "settings": { "port": "8090" },
 *   "inbound": [
 *     { "from": "/switch/press", "event": "switch/press" },
 *     { "from": "/dimmer", "event": "switch/dim", "payload": { "bright": "$.up" }, "policy": "latest" }
 *   ],
 *   "outbound": [
 *     { "property": "bulb/watts", "to": "http://localhost:9000/lamp/watts" },
 *     { "state": "lamp", "to": "http://localhost:9000/lamp/state", "policy": "sample", "periodMs": 500 }
 *   ]
 * }
 * ```
 *
 * `kind` is `http`, `mqtt` or `ros`. `settings` holds what that kind needs. An inbound route names where a message
 * arrives in `from` and the event it becomes in `event`, as the domain and the name with a slash between them. Its
 * `payload` gives each field of the event's payload a value. A text that starts with `$` is a path into the body of
 * the message, and anything else is taken as it is. Without `payload` the body is handed on unchanged. An outbound
 * route names what it follows, which is exactly one of `property` as object and property with a slash between them,
 * `state` as the object a state machine is attached to, or `event` as domain and name, and where the message goes in
 * `to`. `policy` is `queue` with an optional `limit`, `latest`, or `sample` with `periodMs`. Without `policy` a route
 * queues. A route of a ROS bridge may name the type of its messages in `type`, such as `std_msgs/msg/Bool`.
 *
 * The IDE writes this file from a form, and other tools may change it. So reading is tolerant. It reports what is
 * wrong in words and keeps what is right.
 */
public object BridgeConfigFile {

    /** The suffix of the name of a bridge file. */
    public const val SUFFIX: String = ".kbridge.json"

    /** What [text] holds. */
    public fun read(text: String): BridgeConfigReading {
        val root = JsonText.read(text) as? WireValue.WireObject
            ?: return BridgeConfigReading(null, listOf("The file is not a JSON object."))
        val problems = ArrayList<String>()
        val kindText = text(root["kind"])
        val kind = kindText?.let(BridgeKind::of)
        if (kind == null) {
            val known = BridgeKind.entries.joinToString(", ") { it.id }
            return BridgeConfigReading(null, listOf("\"kind\" must be one of $known, but is ${kindText ?: "missing"}."))
        }
        val settings = (root["settings"] as? WireValue.WireObject)?.fields.orEmpty()
            .mapNotNull { field -> text(field.value)?.let { field.name to it } }
            .toMap()
        val inbound = routes(root, "inbound").mapIndexedNotNull { index, route -> inbound(route, "inbound[$index]", problems) }
        val outbound = routes(root, "outbound").mapIndexedNotNull { index, route -> outbound(route, "outbound[$index]", problems) }
        return BridgeConfigReading(BridgeConfig(kind, settings, inbound, outbound), problems)
    }

    /** The text of the bridge file for [config]. [read] reads it back to the same configuration. */
    public fun write(config: BridgeConfig): String {
        val root = JsonText.objectOf(
            "kind" to WireValue.WireString(config.kind.id),
            "settings" to WireValue.WireObject(config.settings.map { WireField(it.key, WireValue.WireString(it.value)) }),
            "inbound" to WireValue.WireArray(config.inbound.map(::inboundJson)),
            "outbound" to WireValue.WireArray(config.outbound.map(::outboundJson)),
        )
        return JsonText.writePretty(root) + "\n"
    }

    private fun routes(root: WireValue.WireObject, name: String): List<WireValue> = (root[name] as? WireValue.WireArray)?.items.orEmpty()

    private fun inbound(value: WireValue, place: String, problems: MutableList<String>): InboundRoute? {
        val route = value as? WireValue.WireObject ?: return problem(problems, "$place is not an object.")
        val from = text(route["from"])?.takeIf { it.isNotBlank() } ?: return problem(problems, "$place needs \"from\".")
        val event = pair(text(route["event"])) ?: return problem(problems, "$place needs \"event\" as domain/name.")
        val payload = when (val fields = route["payload"]) {
            null -> null
            is WireValue.WireObject -> fields.fields.map { field ->
                PayloadField(field.name, source(field.value) ?: return problem(problems, "$place: the payload field \"${field.name}\" is no valid path."))
            }
            else -> return problem(problems, "$place: \"payload\" must be an object.")
        }
        val flow = flow(route, place, problems) ?: return null
        return InboundRoute(from, event.first, event.second, payload, flow, messageType(route))
    }

    private fun outbound(value: WireValue, place: String, problems: MutableList<String>): OutboundRoute? {
        val route = value as? WireValue.WireObject ?: return problem(problems, "$place is not an object.")
        val to = text(route["to"])?.takeIf { it.isNotBlank() } ?: return problem(problems, "$place needs \"to\".")
        val sources = listOfNotNull(
            route["property"]?.let { pair(text(it), last = true)?.let { (objectId, property) -> OutboundSource.Property(objectId, property) } },
            route["state"]?.let { text(it)?.takeIf(String::isNotBlank)?.let(OutboundSource::State) },
            route["event"]?.let { pair(text(it))?.let { (domain, event) -> OutboundSource.Event(domain, event) } },
        )
        val named = listOf("property", "state", "event").count { route[it] != null }
        if (named != 1 || sources.size != 1) {
            return problem(problems, "$place needs exactly one of \"property\" as object/property, \"state\" or \"event\" as domain/name.")
        }
        val flow = flow(route, place, problems) ?: return null
        return OutboundRoute(sources.single(), to, flow, messageType(route))
    }

    /** The type of the messages of [route], or null if it names none. */
    private fun messageType(route: WireValue.WireObject): String? = text(route["type"])?.trim()?.takeIf { it.isNotEmpty() }

    /** The flow policy of [route], or null after reporting why it has none. */
    private fun flow(route: WireValue.WireObject, place: String, problems: MutableList<String>): FlowPolicy? =
        when (val policy = text(route["policy"])?.lowercase()) {
            null, "queue" -> {
                val limit = (route["limit"] as? WireValue.WireNumber)?.value?.toInt() ?: FlowPolicy.DEFAULT_LIMIT
                if (limit > 0) FlowPolicy.Queue(limit) else problem(problems, "$place: \"limit\" must be positive.")
            }
            "latest" -> FlowPolicy.Latest
            "sample" -> {
                val period = (route["periodMs"] as? WireValue.WireNumber)?.value?.toLong()
                if (period != null && period > 0) FlowPolicy.Sample(period) else problem(problems, "$place: the policy sample needs a positive \"periodMs\".")
            }
            else -> problem(problems, "$place: the policy \"$policy\" is not known. Use queue, latest or sample.")
        }

    /** Where the value of a payload field comes from: a path if the value is a text that starts with `$`. */
    private fun source(value: WireValue): ValueSource? {
        val text = (value as? WireValue.WireString)?.value
        return if (text != null && JsonPath.looksLikePath(text)) JsonPath.parse(text)?.let(ValueSource::FromMessage) else ValueSource.Fixed(value)
    }

    /** The two parts of [text] around a slash, the first slash or with [last] the last one, or null without both. */
    private fun pair(text: String?, last: Boolean = false): Pair<String, String>? {
        val slash = (if (last) text?.lastIndexOf('/') else text?.indexOf('/')) ?: return null
        if (slash <= 0 || slash == text!!.length - 1) return null
        return text.substring(0, slash) to text.substring(slash + 1)
    }

    /** The text of a string, a number or a truth value, or null for anything else. */
    private fun text(value: WireValue?): String? = when (value) {
        is WireValue.WireString -> value.value
        is WireValue.WireNumber, is WireValue.WireBoolean -> JsonText.write(value)
        else -> null
    }

    /** Records [message] and returns null, so a caller can write `return problem(...)`. */
    private fun <T> problem(problems: MutableList<String>, message: String): T? {
        problems += message
        return null
    }

    private fun inboundJson(route: InboundRoute): WireValue {
        val fields = ArrayList<WireField>()
        fields += WireField("from", WireValue.WireString(route.from))
        fields += WireField("event", WireValue.WireString("${route.domain}/${route.event}"))
        route.payload?.let { payload ->
            fields += WireField("payload", WireValue.WireObject(payload.map { WireField(it.name, sourceJson(it.source)) }))
        }
        fields += typeJson(route.messageType)
        fields += flowJson(route.flow)
        return WireValue.WireObject(fields)
    }

    private fun outboundJson(route: OutboundRoute): WireValue {
        val source = when (val followed = route.source) {
            is OutboundSource.Property -> WireField("property", WireValue.WireString("${followed.objectId}/${followed.property}"))
            is OutboundSource.State -> WireField("state", WireValue.WireString(followed.machine))
            is OutboundSource.Event -> WireField("event", WireValue.WireString("${followed.domain}/${followed.event}"))
        }
        val to = WireField("to", WireValue.WireString(route.to))
        return WireValue.WireObject(listOf(source, to) + typeJson(route.messageType) + flowJson(route.flow))
    }

    private fun sourceJson(source: ValueSource): WireValue = when (source) {
        is ValueSource.FromMessage -> WireValue.WireString(source.path.text)
        is ValueSource.Fixed -> source.value
    }

    /** The member that writes the message [type] of a route. No type writes nothing. */
    private fun typeJson(type: String?): List<WireField> = listOfNotNull(type?.let { WireField("type", WireValue.WireString(it)) })

    /** The members that write [flow]. A queue of the default size writes nothing. */
    private fun flowJson(flow: FlowPolicy): List<WireField> = when (flow) {
        is FlowPolicy.Queue ->
            if (flow.limit == FlowPolicy.DEFAULT_LIMIT) {
                emptyList()
            } else {
                listOf(WireField("policy", WireValue.WireString("queue")), WireField("limit", WireValue.WireNumber(flow.limit.toDouble())))
            }
        FlowPolicy.Latest -> listOf(WireField("policy", WireValue.WireString("latest")))
        is FlowPolicy.Sample ->
            listOf(WireField("policy", WireValue.WireString("sample")), WireField("periodMs", WireValue.WireNumber(flow.periodMs.toDouble())))
    }
}
