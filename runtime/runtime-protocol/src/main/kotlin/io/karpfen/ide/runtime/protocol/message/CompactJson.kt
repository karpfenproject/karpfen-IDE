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

/**
 * Writes a [WireValue] back as compact JSON. The output has no whitespace. Fields keep their order, and repeated names
 * are kept. Strings are quoted with [RuntimeJson.quote]. Numbers are written the way the runtime writes its doubles. An
 * integral number has no fraction, for example `5` or `1761560130123`. Every other number is written as
 * `Double.toString` writes it, for example `0.2`, `1.0E20`, `NaN`, `Infinity` or `-Infinity`. Reading the result with
 * [RuntimeJson.read] gives the same value back. The one exception is `-0.0`, which is written as `0`, as the runtime
 * writes it. The object holds no state.
 */
internal object CompactJson {

    /** [value] as compact JSON text. */
    fun write(value: WireValue): String = buildString { appendValue(value) }

    /** Appends [value] to this builder. */
    private fun StringBuilder.appendValue(value: WireValue) {
        when (value) {
            is WireValue.WireObject -> {
                append('{')
                value.fields.forEachIndexed { index, field ->
                    if (index > 0) append(',')
                    append(RuntimeJson.quote(field.name)).append(':')
                    appendValue(field.value)
                }
                append('}')
            }
            is WireValue.WireArray -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is WireValue.WireString -> append(RuntimeJson.quote(value.value))
            is WireValue.WireNumber -> append(number(value.value))
            is WireValue.WireBoolean -> append(value.value)
            WireValue.WireNull -> append("null")
        }
    }

    /** [value] as the runtime writes a double. */
    private fun number(value: Double): String =
        if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
