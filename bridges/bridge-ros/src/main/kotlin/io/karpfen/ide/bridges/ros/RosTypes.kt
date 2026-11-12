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
package io.karpfen.ide.bridges.ros

import io.karpfen.ide.bridges.api.JsonText
import io.karpfen.ide.runtime.protocol.message.WireValue

/**
 * How the types of ROS messages are written, and how a value of the model becomes a message of such a type.
 *
 * ROS 2 writes a type as package, category and name, such as `std_msgs/msg/Float64`. ROS 1 knows no category and
 * writes `std_msgs/Float64`. A bridge file may use either form. [written] turns it into the form of the system the
 * bridge talks to, so switching a bridge between ROS 2 and ROS 1 needs no change of its routes.
 */
public object RosTypes {

    /** The type of the messages of an outbound route that names none: a text, which can carry any value. */
    public const val DEFAULT: String = "std_msgs/msg/String"

    /** The category of message types in ROS 2. */
    private const val CATEGORY = "msg"

    /** The name of the member that the messages of the standard types keep their one value in. */
    private const val DATA = "data"

    /** The name of the standard type that carries a text. */
    private const val TEXT = "String"

    /** [type] as a ROS 1 system writes it if [ros1] is true, and as a ROS 2 system writes it otherwise. */
    public fun written(type: String, ros1: Boolean): String {
        val parts = type.trim().split('/').filter { it.isNotEmpty() }
        return when {
            ros1 && parts.size == FULL && parts[1] == CATEGORY -> "${parts[0]}/${parts[2]}"
            !ros1 && parts.size == SHORT -> "${parts[0]}/$CATEGORY/${parts[1]}"
            else -> type.trim()
        }
    }

    /**
     * The message of [type] that carries the JSON [body] a route sends. A JSON object is the message itself, so an
     * event with a payload can fill a message with several members. Any other value goes into the member `data`, as
     * the standard types such as `std_msgs/msg/Float64` and `std_msgs/msg/Bool` expect. For the text type
     * `std_msgs/msg/String` a value that is no text is written as its JSON text, so the active states of a state
     * machine arrive as `["on"]`. A body that is no JSON is sent as a text.
     */
    public fun messageOf(body: String, type: String): WireValue.WireObject {
        val value = JsonText.read(body) ?: WireValue.WireString(body)
        if (value is WireValue.WireObject) return value
        val isText = type.substringAfterLast('/') == TEXT
        val data = if (isText && value !is WireValue.WireString) WireValue.WireString(JsonText.write(value)) else value
        return JsonText.objectOf(DATA to data)
    }

    /** The number of parts of a type with a category. */
    private const val FULL = 3

    /** The number of parts of a type without a category. */
    private const val SHORT = 2
}
