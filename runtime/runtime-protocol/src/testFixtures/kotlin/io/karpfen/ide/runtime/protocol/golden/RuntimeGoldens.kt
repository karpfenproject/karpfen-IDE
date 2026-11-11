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

/**
 * Exact frames and bodies of the karpfen runtime with IDE support, byte for byte. Each one says where it comes from,
 * which is the runtime function that writes it and the example it shows. Tests decode them and program the fake runtime
 * with them. `TemplateGoldenTest` compares the runtime's own templates and [RuntimeWireWriter] with them. The live
 * contract compares the runtime's actual answers with them. A golden changes only together with the runtime and its
 * anchors.
 *
 * The push examples belong to the environment [ENVIRONMENT] and the client [CLIENT], whose id has the IDE's shape.
 */
public object RuntimeGoldens {

    /** The environment key of the push examples, in the runtime's shape `env-<epoch millis>`. */
    public const val ENVIRONMENT: String = "env-1761560123456"

    /** The client id of the push examples, in the IDE's shape `karpfen-ide-<12 hex digits>`. */
    public const val CLIENT: String = "karpfen-ide-4f2c9a1b7e03"

    /**
     * The object JSON of `turtlePosition` of the single robot example after a change, as `ModelJsonWriter.write` writes
     * it. Entries are separated by `", "`, keys are followed by `": "`, and integral numbers have no fraction.
     */
    public const val TURTLE_POSITION: String = """{"__id__": "turtlePosition", "__type__": "Vector", "x": 5, "y": 5.3}"""

    /**
     * The `objectChanged` payload of [TURTLE_POSITION] at revision 58, as `ClientSessionManager.notifyObjectChange`
     * writes it with a revision. The members are `objectId`, `revision` and `value` in this order, and the value is
     * spliced in.
     */
    public const val OBJECT_CHANGED_PAYLOAD: String = """{"objectId":"turtlePosition","revision":58,"value":$TURTLE_POSITION}"""

    /** The `objectChanged` push of [OBJECT_CHANGED_PAYLOAD], as `OutgoingMessage.toJson` wraps it. */
    public const val OBJECT_CHANGED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"objectChanged","payload":$OBJECT_CHANGED_PAYLOAD}"""

    /**
     * The `objectDeleted` payload of the run-time object `gen_Drone_4` at revision 59, as
     * `ClientSessionManager.notifyObjectDeleted` writes it with a revision.
     */
    public const val OBJECT_DELETED_PAYLOAD: String = """{"objectId":"gen_Drone_4","revision":59}"""

    /** The `objectDeleted` push of [OBJECT_DELETED_PAYLOAD], as `OutgoingMessage.toJson` wraps it. */
    public const val OBJECT_DELETED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"objectDeleted","payload":$OBJECT_DELETED_PAYLOAD}"""

    /**
     * The `stateChange` payload of the machine attached to `turtle` with the stack `["observe"]` at revision 60, as
     * `ObservationRelay.onActiveStackChanged` builds it with org.json 20240303, the version the runtime uses. org.json
     * writes the members in the order `stack`, `modelElementId`, `revision`.
     */
    public const val STATE_CHANGE_PAYLOAD: String = """{"stack":["observe"],"modelElementId":"turtle","revision":60}"""

    /** The `stateChange` push of [STATE_CHANGE_PAYLOAD], as `OutgoingMessage.toJson` wraps it. */
    public const val STATE_CHANGE: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"stateChange","payload":$STATE_CHANGE_PAYLOAD}"""

    /**
     * The published event `start` of the domain `public`, injected over the WebSocket without a payload, as
     * `ObservationRelay.onPublished` builds it with org.json 20240303. The members come in org.json's order `payload`,
     * `source`, `ttlMs`, `event`, `payloadFormat`, `timestamp`.
     */
    public const val PUBLISHED_EVENT: String =
        """{"payload":"","source":"EXTERNAL_MESSAGE","ttlMs":3000,"event":"start","payloadFormat":"none","timestamp":1761560130123}"""

    /**
     * The `domainEvent` payload of [PUBLISHED_EVENT], as `ClientSessionManager.notifyDomainEvent` writes it. The domain
     * is quoted, and the published event is spliced in.
     */
    public const val DOMAIN_EVENT_PAYLOAD: String = """{"domain":"public","payload":$PUBLISHED_EVENT}"""

    /** The `domainEvent` push of [DOMAIN_EVENT_PAYLOAD], as `OutgoingMessage.toJson` wraps it. */
    public const val DOMAIN_EVENT: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"domainEvent","payload":$DOMAIN_EVENT_PAYLOAD}"""

    /**
     * A handshake that the runtime's `/ws` handler accepts. It is the client id, the environment key and the access key
     * joined by `:`. The access key has the shape `ak-<clientId>-<millis>-<0..9999>` that `ClientSessionManager`
     * issues.
     */
    public const val HANDSHAKE: String = "ide-4f2c:env-1761555600000:ak-ide-4f2c-1761555601234-4821"

    /**
     * The event frame that injects `start` of the domain `public` without a payload, as the client encodes it and the
     * runtime's `WebSocketMessage.fromJson` reads it.
     */
    public const val START_EVENT_FRAME: String =
        """{"environmentKey":"public","messageType":"start","payload":"","payloadFormat":"none"}"""

    /**
     * The event frame that injects `assignDelivery` of the domain `control` with a JSON payload. The payload text is
     * quoted as a JSON string.
     */
    public const val ASSIGN_DELIVERY_EVENT_FRAME: String =
        """{"environmentKey":"control","messageType":"assignDelivery",""" +
            """"payload":"{\"packageId\":\"PKG-42\",\"priority\":2,\"destination\":{\"x\":12.0,\"y\":8.0}}","payloadFormat":"json"}"""

    /**
     * The root object `living room` of the synthesized lamp model before the start, as the runtime parses the lamp's
     * model and `ModelJsonWriter.write` writes it. It holds only the properties the model writes, and the `has`
     * children are nested. The switch that the lamp `knows` is written in full where the lamp refers to it, and again
     * as the room's own child.
     */
    public const val LAMP_ROOT: String =
        """{"__id__": "living room", "__type__": "Room", "lamp": {"__id__": "lamp", "__type__": "Lamp", "mode": "off", """ +
            """"bulb": {"__id__": "bulb", "__type__": "Bulb", "watts": 40, "lit": false}, "spare": {"__id__": "spare", """ +
            """"__type__": "Bulb", "watts": 60, "lit": false}, "control": {"__id__": "wallSwitch", "__type__": "Switch", """ +
            """"position": "down"}}, "switch": {"__id__": "wallSwitch", "__type__": "Switch", "position": "down"}}"""

    /**
     * The snapshot of an activated lamp environment [ENVIRONMENT] before its start, as `ModelSnapshot.toJson` writes
     * it. It has the phase `activated`, the revision 0, no active stacks yet, and the [LAMP_ROOT].
     */
    public const val LAMP_SNAPSHOT_BEFORE_START: String =
        """{"environmentKey":"$ENVIRONMENT","phase":"activated","revision":0,"activeStates":{},"roots":[$LAMP_ROOT]}"""

    /**
     * The full value of `turtle` of the single robot example, as `ModelJsonWriter.write` writes it. These are the bytes
     * that the runtime's own `ModelJsonWriterTest` holds for the cleaning robot. The `has` children are nested, and the
     * `knows` targets are written in full where they are referred to. So `chair` and `wall_top` appear twice in one
     * value.
     */
    public const val TURTLE: String =
        """{"__id__": "turtle", "__type__": "Robot", "d_closest_obstacle": 100, "d_closest_wall": 100, """ +
            """"boundingBox": {"__id__": "turtleBoundingBox", "__type__": "TwoDObject", "diameter": 0.2, """ +
            """"position": {"__id__": "turtlePosition", "__type__": "Vector", "x": 5, "y": 5}}, """ +
            """"direction": {"__id__": "turtleDirection", "__type__": "Vector", "x": 0, "y": 1}, "obstacles": [{"__id__": "chair", """ +
            """"__type__": "Obstacle", "boundingBox": {"__id__": "chairBoundingBox", "__type__": "TwoDObject", "diameter": 1, """ +
            """"position": {"__id__": "chairPosition", "__type__": "Vector", "x": 2, "y": 3}}}, {"__id__": "table", """ +
            """"__type__": "Obstacle", "boundingBox": {"__id__": "tableBoundingBox", "__type__": "TwoDObject", "diameter": 3, """ +
            """"position": {"__id__": "tablePosition", "__type__": "Vector", "x": 5, "y": 7}}}], "walls": [{"__id__": "wall_top", """ +
            """"__type__": "Wall", "p": {"__id__": "wallTopP", "__type__": "Vector", "x": 0, "y": 10}, """ +
            """"u_vec": {"__id__": "wallTopUVec", "__type__": "Vector", "x": 1, "y": 0}}, {"__id__": "wall_right", """ +
            """"__type__": "Wall", "p": {"__id__": "wallRightP", "__type__": "Vector", "x": 10, "y": 0}, """ +
            """"u_vec": {"__id__": "wallRightUVec", "__type__": "Vector", "x": 0, "y": 1}}, {"__id__": "wall_bottom", """ +
            """"__type__": "Wall", "p": {"__id__": "wallBottomP", "__type__": "Vector", "x": 0, "y": 0}, """ +
            """"u_vec": {"__id__": "wallBottomUVec", "__type__": "Vector", "x": 1, "y": 0}}, {"__id__": "wall_left", """ +
            """"__type__": "Wall", "p": {"__id__": "wallLeftP", "__type__": "Vector", "x": 0, "y": 0}, """ +
            """"u_vec": {"__id__": "wallLeftUVec", "__type__": "Vector", "x": 0, "y": 1}}], "closest_obstacle": {"__id__": "chair", """ +
            """"__type__": "Obstacle", "boundingBox": {"__id__": "chairBoundingBox", "__type__": "TwoDObject", "diameter": 1, """ +
            """"position": {"__id__": "chairPosition", "__type__": "Vector", "x": 2, "y": 3}}}, """ +
            """"closest_wall": {"__id__": "wall_top", "__type__": "Wall", "p": {"__id__": "wallTopP", "__type__": "Vector", "x": 0, """ +
            """"y": 10}, "u_vec": {"__id__": "wallTopUVec", "__type__": "Vector", "x": 1, "y": 0}}}"""

    /** The `objectChanged` push of the full [TURTLE] at revision 61. */
    public const val TURTLE_CHANGED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"objectChanged",""" +
            """"payload":{"objectId":"turtle","revision":61,"value":$TURTLE}}"""

    /**
     * An object whose string value holds an escaped newline, backslash and quote and the control character U+0001, as
     * `ModelJsonWriter.quote` escapes them. It uses the short forms where JSON has them, and `\u0001` otherwise.
     */
    public const val ESCAPES_VALUE: String =
        """{"__id__": "note", "__type__": "Note", "text": "line one\nback\\slash \"quoted\" \u0001 end"}"""

    /** The `objectChanged` push of [ESCAPES_VALUE] at revision 62. */
    public const val ESCAPES_CHANGED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"objectChanged",""" +
            """"payload":{"objectId":"note","revision":62,"value":$ESCAPES_VALUE}}"""

    /**
     * An object with numbers that JSON cannot write, written the way the runtime writes doubles with `Double.toString`.
     * These are the bare literals `NaN`, `Infinity` and `-Infinity`, and `1.0E20`.
     */
    public const val NON_FINITE_VALUE: String =
        """{"__id__": "sensor", "__type__": "Sensor", "nan": NaN, "readings": [Infinity, -Infinity, 1.0E20]}"""

    /** The `objectChanged` push of [NON_FINITE_VALUE] at revision 63. */
    public const val NON_FINITE_CHANGED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"objectChanged",""" +
            """"payload":{"objectId":"sensor","revision":63,"value":$NON_FINITE_VALUE}}"""

    /**
     * An object with an anonymous nested object, whose id is written as `"__id__": ""`. It also has an empty atomic
     * relation written as `null` and a list relation with an unresolved entry written as `null`.
     */
    public const val RELATIONS_VALUE: String =
        """{"__id__": "robot", "__type__": "Robot", "direction": {"__id__": "", "__type__": "Vector", "x": 1}, "battery": null, """ +
            """"wheels": [{"__id__": "w1", "__type__": "Wheel"}, null]}"""

    /** The `objectChanged` push of [RELATIONS_VALUE] at revision 64. */
    public const val RELATIONS_CHANGED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"objectChanged",""" +
            """"payload":{"objectId":"robot","revision":64,"value":$RELATIONS_VALUE}}"""

    /**
     * A `stateChange` push whose payload members come in another order than org.json writes them, namely
     * `modelElementId`, `revision`, `stack`. The stack has two states, and one of them has a space. Readers must not
     * depend on the order.
     */
    public const val STATE_CHANGE_REORDERED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"stateChange",""" +
            """"payload":{"modelElementId":"lamp","revision":65,"stack":["on","full power"]}}"""

    /**
     * A `domainEvent` push whose published event lists its members in the documented order `event`, `payload`,
     * `payloadFormat`, `source`, `timestamp`, `ttlMs`, rather than in org.json's order. Readers must not depend on the
     * order.
     */
    public const val DOMAIN_EVENT_REORDERED: String =
        """{"environmentKey":"$ENVIRONMENT","clientId":"$CLIENT","messageType":"domainEvent","payload":{"domain":"switch",""" +
            """"payload":{"event":"press","payload":"","payloadFormat":"none","source":"EXTERNAL_MESSAGE","timestamp":1761560130123,""" +
            """"ttlMs":30000}}}"""

    /** Every push golden of [ENVIRONMENT] and [CLIENT], in the order of their declarations. */
    public val PUSHES: List<String> = listOf(
        OBJECT_CHANGED, OBJECT_DELETED, STATE_CHANGE, DOMAIN_EVENT, TURTLE_CHANGED, ESCAPES_CHANGED, NON_FINITE_CHANGED,
        RELATIONS_CHANGED, STATE_CHANGE_REORDERED, DOMAIN_EVENT_REORDERED,
    )
}
