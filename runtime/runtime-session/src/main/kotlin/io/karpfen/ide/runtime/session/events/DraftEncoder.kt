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

import io.karpfen.ide.runtime.protocol.message.RuntimeJson

/**
 * Writes a [PayloadDraft] as the compact JSON object its [PayloadForm] describes, fields in form order. Stateless.
 */
internal object DraftEncoder {
    /** The JSON object of [draft] filled into [form]. */
    fun encode(form: PayloadForm, draft: PayloadDraft): String = obj(form, emptyList(), draft)

    /** The JSON object of the fields of [form] below [path]. */
    private fun obj(form: PayloadForm, path: List<String>, draft: PayloadDraft): String {
        val members = form.fields.mapNotNull { field ->
            val fieldPath = path + field.key
            val value = when (field) {
                is PayloadField.Scalar -> draft.values[fieldPath]?.let(::value)
                is PayloadField.Nested -> nested(field, fieldPath, draft)
            }
            value?.let { "${RuntimeJson.quote(field.key)}:$it" }
        }
        return members.joinToString(",", "{", "}")
    }

    /** The JSON of the relation [field] at [path], or null if the draft fills in nothing below it. */
    private fun nested(field: PayloadField.Nested, path: List<String>, draft: PayloadDraft): String? {
        val below = draft.values.keys.filter { it.size > path.size && it.subList(0, path.size) == path }
        if (below.isEmpty()) return null
        if (!field.isList) return obj(field.form(), path, draft)
        val indices = below.mapNotNull { it[path.size].toIntOrNull() }.distinct().sorted()
        return indices.joinToString(",", "[", "]") { obj(field.form(), path + it.toString(), draft) }
    }

    /** The JSON of a draft value. */
    private fun value(value: PayloadDraftValue): String =
        when (value) {
            is PayloadDraftValue.Single -> scalar(value.value)
            is PayloadDraftValue.Many -> value.values.joinToString(",", "[", "]", transform = ::scalar)
        }

    /** The JSON of a scalar; a number that is not finite is written as a string. */
    private fun scalar(value: PayloadScalar): String =
        when (value) {
            is PayloadScalar.Number -> number(value.value)
            is PayloadScalar.Text -> RuntimeJson.quote(value.value)
            is PayloadScalar.Flag -> value.value.toString()
        }

    /** A finite number without fraction when it is integral, otherwise as Kotlin writes a double; others as a string. */
    private fun number(value: Double): String =
        when {
            !value.isFinite() -> RuntimeJson.quote(value.toString())
            value == Math.rint(value) && Math.abs(value) < LONG_SAFE -> value.toLong().toString()
            else -> value.toString()
        }

    /** The magnitude below which an integral double is written as a whole number. */
    private const val LONG_SAFE = 1.0e15
}
