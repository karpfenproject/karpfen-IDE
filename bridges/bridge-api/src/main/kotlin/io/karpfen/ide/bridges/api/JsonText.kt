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
package io.karpfen.ide.bridges.api

import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.WireField
import io.karpfen.ide.runtime.protocol.message.WireValue

/**
 * JSON as the bridges read and write it. The bridges use the JSON values of the runtime protocol, so a value that
 * comes from the model goes out to the external system without being converted in between.
 */
public object JsonText {

    /** The JSON value that [text] holds, or null if [text] is not JSON. */
    public fun read(text: String): WireValue? = (RuntimeJson.read(text) as? JsonReading.Read)?.value

    /**
     * [value] as JSON text on one line. A number without a fraction is written without a decimal point. A number that
     * JSON cannot hold, which is not finite, is written as `null`.
     */
    public fun write(value: WireValue): String = buildString { write(value, this) }

    /**
     * [value] as JSON text over several lines, indented by two blanks per level, for a file that people may look at.
     * Empty objects and arrays stay on one line.
     */
    public fun writePretty(value: WireValue): String = buildString { writePretty(value, this, 0) }

    /** An object with [fields] in their order. */
    public fun objectOf(vararg fields: Pair<String, WireValue>): WireValue.WireObject =
        WireValue.WireObject(fields.map { WireField(it.first, it.second) })

    private fun write(value: WireValue, out: StringBuilder) {
        when (value) {
            is WireValue.WireObject -> {
                out.append('{')
                value.fields.forEachIndexed { index, field ->
                    if (index > 0) out.append(',')
                    out.append(RuntimeJson.quote(field.name)).append(':')
                    write(field.value, out)
                }
                out.append('}')
            }
            is WireValue.WireArray -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            else -> out.append(scalar(value))
        }
    }

    private fun writePretty(value: WireValue, out: StringBuilder, level: Int) {
        val inner = INDENT.repeat(level + 1)
        when {
            value is WireValue.WireObject && value.fields.isNotEmpty() -> {
                out.append("{\n")
                value.fields.forEachIndexed { index, field ->
                    out.append(inner).append(RuntimeJson.quote(field.name)).append(": ")
                    writePretty(field.value, out, level + 1)
                    out.append(if (index < value.fields.lastIndex) ",\n" else "\n")
                }
                out.append(INDENT.repeat(level)).append('}')
            }
            value is WireValue.WireArray && value.items.isNotEmpty() -> {
                out.append("[\n")
                value.items.forEachIndexed { index, item ->
                    out.append(inner)
                    writePretty(item, out, level + 1)
                    out.append(if (index < value.items.lastIndex) ",\n" else "\n")
                }
                out.append(INDENT.repeat(level)).append(']')
            }
            else -> write(value, out)
        }
    }

    /** The text of a value that is neither an object nor an array. */
    private fun scalar(value: WireValue): String = when (value) {
        is WireValue.WireString -> RuntimeJson.quote(value.value)
        is WireValue.WireBoolean -> value.value.toString()
        is WireValue.WireNumber -> number(value.value)
        else -> "null"
    }

    private fun number(value: Double): String = when {
        !value.isFinite() -> "null"
        value == Math.rint(value) && Math.abs(value) < WHOLE_LIMIT -> value.toLong().toString()
        else -> value.toString()
    }

    /** The indentation of one level. */
    private const val INDENT = "  "

    /** Whole numbers below this size are written without a decimal point. Above it a Double is no longer exact. */
    private const val WHOLE_LIMIT = 9.007199254740992E15
}
