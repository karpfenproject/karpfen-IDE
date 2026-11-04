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
package io.karpfen.ide.runtime.protocol.message

import io.karpfen.ide.runtime.protocol.catalogue.WireNames

/**
 * Writes the text frame of an [EventFrame] exactly as
 * `{"environmentKey":<domain>,"messageType":<name>,"payload":<text>,"payloadFormat":<format>}`: in this member order,
 * without whitespace, every value a string quoted with [RuntimeJson.quote]. The payload is always a JSON string, never a
 * spliced object, and the format is always explicit (`json`, `kmodel`, or `none` with an empty payload), so the runtime
 * never has to detect it. The runtime reads the frame's `environmentKey` as the event's domain and its `messageType` as
 * the event's name. Stateless.
 */
public object EventFrameEncoder {

    /** The text frame of [frame]. */
    public fun encode(frame: EventFrame): String {
        val (text, format) = when (val payload = frame.payload) {
            EventPayload.None -> "" to WireNames.FORMAT_NONE
            is EventPayload.Json -> payload.text to WireNames.FORMAT_JSON
            is EventPayload.Kmodel -> payload.text to WireNames.FORMAT_KMODEL
        }
        return buildString {
            append('{')
            member(WireNames.ENVIRONMENT_KEY, frame.domain)
            append(',')
            member(WireNames.MESSAGE_TYPE, frame.name)
            append(',')
            member(WireNames.PAYLOAD, text)
            append(',')
            member(WireNames.PAYLOAD_FORMAT, format)
            append('}')
        }
    }

    /** Appends the member [name] with the string [value]. */
    private fun StringBuilder.member(name: String, value: String) {
        append(RuntimeJson.quote(name)).append(':').append(RuntimeJson.quote(value))
    }
}
