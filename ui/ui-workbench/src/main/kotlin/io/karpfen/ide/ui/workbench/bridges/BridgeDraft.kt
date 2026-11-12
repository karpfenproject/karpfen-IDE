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
package io.karpfen.ide.ui.workbench.bridges

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
import io.karpfen.ide.bridges.http.HttpSettings
import io.karpfen.ide.bridges.mqtt.MqttSettings
import io.karpfen.ide.bridges.ros.RosSettings
import io.karpfen.ide.runtime.protocol.message.WireValue

/**
 * A bridge file as the form edits it. Every value is the text of a field, so the draft can hold what the user is still
 * typing, such as a route without an address. [BridgeDrafts] turns a configuration into a draft, says what is wrong
 * with a draft, and turns a draft without problems back into a configuration.
 *
 * @property kind the kind of bridge.
 * @property settings the settings of that kind by their keys. A blank value means the default.
 * @property inbound the routes that turn a message into an event.
 * @property outbound the routes that report what the model does.
 */
public data class BridgeDraft(
    public val kind: BridgeKind = BridgeKind.HTTP,
    public val settings: Map<String, String> = emptyMap(),
    public val inbound: List<InboundDraft> = emptyList(),
    public val outbound: List<OutboundDraft> = emptyList(),
)

/**
 * An inbound route in the form.
 *
 * @property from where the message arrives: a path for HTTP, a topic filter for MQTT.
 * @property event the event the message becomes, as the domain and the name with a slash between them.
 * @property passBody true if the body of the message is handed on as the payload. Then [fields] are not used.
 * @property fields the fields of the payload when the body is not handed on. No field means an event without payload.
 * @property policy what happens when messages arrive faster than the model takes them.
 * @property type the type of the messages at [from], which only a ROS bridge uses, such as `std_msgs/msg/Bool`.
 *   Blank lets ROS find the type.
 */
public data class InboundDraft(
    public val from: String = "",
    public val event: String = "",
    public val passBody: Boolean = true,
    public val fields: List<FieldDraft> = emptyList(),
    public val policy: PolicyDraft = PolicyDraft(),
    public val type: String = "",
)

/**
 * One field of the payload of an event.
 *
 * @property name the name of the field.
 * @property value where its value comes from. A text that starts with `$` is a path into the body of the message, such
 *   as `$.bright`. `true`, `false` and a number are taken as such. A text in double quotes and any other text is text.
 */
public data class FieldDraft(public val name: String = "", public val value: String = "")

/** What an outbound route follows. */
public enum class Follows {
    /** A property of an object, written as the object and the property with a slash between them. */
    PROPERTY,

    /** The active states of a state machine, named by the object the machine is attached to. */
    STATE,

    /** An event that a state machine raises, written as the domain and the name with a slash between them. */
    EVENT,
}

/**
 * An outbound route in the form.
 *
 * @property follows what the route follows.
 * @property what the followed thing, written as [follows] says.
 * @property to where the message goes: a URL for HTTP, a topic for MQTT.
 * @property policy what happens when the model reports faster than the outside takes it.
 * @property type the type of the messages at [to], which only a ROS bridge uses, such as `std_msgs/msg/Float64`.
 *   Blank means a text.
 */
public data class OutboundDraft(
    public val follows: Follows = Follows.PROPERTY,
    public val what: String = "",
    public val to: String = "",
    public val policy: PolicyDraft = PolicyDraft(),
    public val type: String = "",
)

/** How a route deals with more messages than it can hand on. */
public enum class PolicyKind {
    /** Messages wait in a line, and the oldest is dropped when the line is full. */
    QUEUE,

    /** Only the latest message waits. */
    LATEST,

    /** At most one message per period is handed on, the latest one. */
    SAMPLE,
}

/**
 * The flow policy of a route in the form.
 *
 * @property kind the policy.
 * @property number the length of the line for [PolicyKind.QUEUE], where blank means the default, or the period in
 *   milliseconds for [PolicyKind.SAMPLE]. It is not used for [PolicyKind.LATEST].
 */
public data class PolicyDraft(public val kind: PolicyKind = PolicyKind.QUEUE, public val number: String = "")

/**
 * One setting of a kind of bridge, as the form asks for it.
 *
 * @property key the key of the setting in the bridge file.
 * @property label what the form calls it.
 * @property hint the value that applies when the field is left empty, shown inside the empty field.
 * @property isFlag true for a setting that is switched on or off.
 * @property isSecret true for a setting whose text the form hides.
 */
public data class BridgeSetting(
    public val key: String,
    public val label: String,
    public val hint: String = "",
    public val isFlag: Boolean = false,
    public val isSecret: Boolean = false,
)

/** Turns bridge configurations into drafts of the form and back, and says what is wrong with a draft. */
public object BridgeDrafts {

    /** The settings the form asks for a bridge of [kind]. */
    public fun settingsOf(kind: BridgeKind): List<BridgeSetting> = when (kind) {
        BridgeKind.HTTP -> listOf(
            BridgeSetting(HttpSettings.HOST, "Host", HttpSettings.DEFAULT_HOST),
            BridgeSetting(HttpSettings.PORT, "Port", HttpSettings.DEFAULT_PORT.toString()),
            BridgeSetting(HttpSettings.TIMEOUT_MS, "Timeout in ms", HttpSettings.DEFAULT_TIMEOUT_MS.toString()),
        )
        BridgeKind.MQTT -> listOf(
            BridgeSetting(MqttSettings.BROKER, "Broker", "tcp://${MqttSettings.DEFAULT_HOST}:${MqttSettings.DEFAULT_PORT}"),
            BridgeSetting(MqttSettings.CLIENT_ID, "Client id", "named after the file"),
            BridgeSetting(MqttSettings.USERNAME, "User name"),
            BridgeSetting(MqttSettings.PASSWORD, "Password", isSecret = true),
            BridgeSetting(MqttSettings.QOS, "QoS (0, 1 or 2)", MqttSettings.DEFAULT_QOS.toString()),
            BridgeSetting(MqttSettings.TIMEOUT_MS, "Timeout in ms", MqttSettings.DEFAULT_TIMEOUT_MS.toString()),
            BridgeSetting(MqttSettings.RETAIN, "The broker keeps the last message of each topic", isFlag = true),
        )
        BridgeKind.ROS -> listOf(
            BridgeSetting(RosSettings.URL, "Rosbridge server", RosSettings.DEFAULT_URL),
            BridgeSetting(RosSettings.TIMEOUT_MS, "Timeout in ms", RosSettings.DEFAULT_TIMEOUT_MS.toString()),
            BridgeSetting(RosSettings.ROS1, "Legacy ROS 1 system", isFlag = true),
        )
    }

    /**
     * The types of ROS messages the form offers to pick for a route, the standard ones with one value. Any other type
     * can be typed in.
     */
    public val messageTypes: List<String> = listOf(
        "std_msgs/msg/Empty",
        "std_msgs/msg/Bool",
        "std_msgs/msg/String",
        "std_msgs/msg/Float64",
        "std_msgs/msg/Int32",
    )

    /** The draft that shows [config]. */
    public fun of(config: BridgeConfig): BridgeDraft = BridgeDraft(
        kind = config.kind,
        settings = config.settings,
        inbound = config.inbound.map { route ->
            InboundDraft(
                from = route.from,
                event = "${route.domain}/${route.event}",
                passBody = route.payload == null,
                fields = route.payload.orEmpty().map { FieldDraft(it.name, textOf(it.source)) },
                policy = draftOf(route.flow),
                type = route.messageType.orEmpty(),
            )
        },
        outbound = config.outbound.map { route ->
            val (follows, what) = when (val source = route.source) {
                is OutboundSource.Property -> Follows.PROPERTY to "${source.objectId}/${source.property}"
                is OutboundSource.State -> Follows.STATE to source.machine
                is OutboundSource.Event -> Follows.EVENT to "${source.domain}/${source.event}"
            }
            OutboundDraft(follows, what, route.to, draftOf(route.flow), route.messageType.orEmpty())
        },
    )

    /**
     * [draft] as a bridge of [kind]. The routes stay. Of the settings only those stay that the new kind has as well,
     * because a port of an HTTP bridge means nothing to an MQTT bridge.
     */
    public fun withKind(draft: BridgeDraft, kind: BridgeKind): BridgeDraft {
        val keys = settingsOf(kind).map { it.key }.toSet()
        return draft.copy(kind = kind, settings = draft.settings.filterKeys { it in keys })
    }

    /**
     * What keeps [draft] from being saved, each in one sentence that names the route by its number in the form. An
     * empty list means that [configOf] returns a configuration.
     */
    public fun problemsOf(draft: BridgeDraft): List<String> = Conversion(draft).problems

    /** The configuration that [draft] describes, or null if [problemsOf] finds something wrong with it. */
    public fun configOf(draft: BridgeDraft): BridgeConfig? = Conversion(draft).let { if (it.problems.isEmpty()) it.config else null }

    /** The text of a payload field's value for [source], which [sourceOf] reads back to the same source. */
    private fun textOf(source: ValueSource): String = when (source) {
        is ValueSource.FromMessage -> source.path.text
        is ValueSource.Fixed -> when (val value = source.value) {
            is WireValue.WireString -> if (readsAsText(value.value)) value.value else JsonText.write(value)
            else -> JsonText.write(value)
        }
    }

    /** True if the form reads [text] as that very text and not as a path, a number, a truth value or a quoted text. */
    private fun readsAsText(text: String): Boolean =
        text == text.trim() && !JsonPath.looksLikePath(text) && scalarOf(text) == null

    /** The number, the truth value or the quoted text that [text] writes, or null if it writes none of them. */
    private fun scalarOf(text: String): WireValue? = when (val value = JsonText.read(text)) {
        is WireValue.WireNumber, is WireValue.WireBoolean, is WireValue.WireString -> value
        else -> null
    }

    /** Where the value of a payload field comes from when the form says [text], or null for a path that is none. */
    private fun sourceOf(text: String): ValueSource? {
        val trimmed = text.trim()
        return if (JsonPath.looksLikePath(trimmed)) {
            JsonPath.parse(trimmed)?.let(ValueSource::FromMessage)
        } else {
            ValueSource.Fixed(scalarOf(trimmed) ?: WireValue.WireString(text))
        }
    }

    private fun draftOf(flow: FlowPolicy): PolicyDraft = when (flow) {
        is FlowPolicy.Queue -> PolicyDraft(PolicyKind.QUEUE, if (flow.limit == FlowPolicy.DEFAULT_LIMIT) "" else flow.limit.toString())
        FlowPolicy.Latest -> PolicyDraft(PolicyKind.LATEST)
        is FlowPolicy.Sample -> PolicyDraft(PolicyKind.SAMPLE, flow.periodMs.toString())
    }

    /** The two parts of [text] around its first slash, or with [last] its last one, or null without both parts. */
    private fun pair(text: String, last: Boolean = false): Pair<String, String>? {
        val trimmed = text.trim()
        val slash = if (last) trimmed.lastIndexOf('/') else trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return null
        return trimmed.substring(0, slash) to trimmed.substring(slash + 1)
    }

    /** One pass over a draft that collects the [problems] and builds the [config] from what is right. */
    private class Conversion(private val draft: BridgeDraft) {
        val problems = ArrayList<String>()
        val config: BridgeConfig

        init {
            val settings = draft.settings.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
            problems += when (draft.kind) {
                BridgeKind.HTTP -> HttpSettings.problemsOf(settings)
                BridgeKind.MQTT -> MqttSettings.problemsOf(settings)
                BridgeKind.ROS -> RosSettings.problemsOf(settings)
            }
            val inbound = draft.inbound.mapIndexedNotNull { index, route -> inbound(route, "Inbound route ${index + 1}") }
            val outbound = draft.outbound.mapIndexedNotNull { index, route -> outbound(route, "Outbound route ${index + 1}") }
            draft.inbound.map { it.from.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys
                .forEach { problems += "Several inbound routes listen at \"$it\". Each address can have one route only." }
            config = BridgeConfig(draft.kind, settings, inbound, outbound)
        }

        private fun inbound(route: InboundDraft, place: String): InboundRoute? {
            val before = problems.size
            if (route.from.isBlank()) problems += "$place needs an address."
            val event = pair(route.event)
            if (event == null) problems += "$place needs an event as domain/name."
            val payload = if (route.passBody) null else route.fields.mapNotNull { field(it, place) }
            if (!route.passBody && route.fields.map { it.name.trim() }.distinct().size < route.fields.size) {
                problems += "$place names a payload field twice."
            }
            val flow = flow(route.policy, place)
            return if (problems.size > before || event == null || flow == null) {
                null
            } else {
                InboundRoute(route.from.trim(), event.first, event.second, payload, flow, typeOf(route.type))
            }
        }

        private fun field(field: FieldDraft, place: String): PayloadField? {
            val name = field.name.trim()
            if (name.isEmpty()) problems += "$place has a payload field without a name."
            val source = sourceOf(field.value)
            if (source == null) problems += "$place: \"${field.value.trim()}\" is no path. A path looks like \$.bright or \$.items[0]."
            return if (name.isEmpty() || source == null) null else PayloadField(name, source)
        }

        private fun outbound(route: OutboundDraft, place: String): OutboundRoute? {
            val source = when (route.follows) {
                Follows.PROPERTY -> pair(route.what, last = true)?.let { OutboundSource.Property(it.first, it.second) }
                    ?: null.also { problems += "$place needs a property as object/property." }
                Follows.STATE -> route.what.trim().takeIf { it.isNotEmpty() }?.let(OutboundSource::State)
                    ?: null.also { problems += "$place needs the object its state machine is attached to." }
                Follows.EVENT -> pair(route.what)?.let { OutboundSource.Event(it.first, it.second) }
                    ?: null.also { problems += "$place needs an event as domain/name." }
            }
            if (route.to.isBlank()) problems += "$place needs a destination."
            val flow = flow(route.policy, place)
            if (source == null || route.to.isBlank() || flow == null) return null
            return OutboundRoute(source, route.to.trim(), flow, typeOf(route.type))
        }

        /** The message type that [text] names, which only a ROS bridge keeps. */
        private fun typeOf(text: String): String? = text.trim().takeIf { it.isNotEmpty() && draft.kind == BridgeKind.ROS }

        private fun flow(policy: PolicyDraft, place: String): FlowPolicy? {
            val number = policy.number.trim()
            return when (policy.kind) {
                PolicyKind.QUEUE -> when {
                    number.isEmpty() -> FlowPolicy.Queue()
                    else -> number.toIntOrNull()?.takeIf { it > 0 }?.let(FlowPolicy::Queue)
                        ?: null.also { problems += "$place: the length of the line must be a positive number." }
                }
                PolicyKind.LATEST -> FlowPolicy.Latest
                PolicyKind.SAMPLE -> number.toLongOrNull()?.takeIf { it > 0 }?.let(FlowPolicy::Sample)
                    ?: null.also { problems += "$place: the period in ms must be a positive number." }
            }
        }
    }
}
