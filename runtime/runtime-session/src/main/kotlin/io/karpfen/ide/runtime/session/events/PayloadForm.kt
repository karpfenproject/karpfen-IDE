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

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.runtime.session.schema.TypeSchema

/** A field of a [PayloadForm]: a member of the payload type. */
public sealed interface PayloadField {
    /** The member's key. */
    public val key: String

    /**
     * A simple property.
     *
     * @property type its simple type.
     * @property isList true for a list property.
     */
    public data class Scalar(override val key: String, public val type: SimplePropertyType, public val isList: Boolean) : PayloadField

    /**
     * A relation, filled in as nested objects: the runtime builds new objects for `has` and `knows` members alike.
     *
     * @property typeName the type of its targets.
     * @property association `has` or `knows`.
     * @property isList true for a list relation.
     * @property form the form of a target, built when it is asked for, so recursive types stay finite.
     */
    public data class Nested(
        override val key: String,
        public val typeName: String,
        public val association: AssociationType,
        public val isList: Boolean,
        public val form: () -> PayloadForm,
    ) : PayloadField
}

/**
 * The fields of a payload type, for a form the user fills in instead of typing JSON.
 *
 * @property typeName the payload type.
 * @property fields its members in declaration order; empty for a type the schema does not know.
 */
public data class PayloadForm(public val typeName: String, public val fields: List<PayloadField>) {
    /**
     * The JSON object [draft] fills in: numbers as JSON numbers, booleans, strings, arrays for list values, nested
     * objects for relations (`has` and `knows` alike); fields the draft leaves out are left out, and draft values for
     * keys the form does not have are ignored. A number that is not finite has no JSON form and is written as a string,
     * which the payload checks report. The JSON then goes through the JSON format's checks like typed text.
     */
    public fun toJson(draft: PayloadDraft): String = DraftEncoder.encode(this, draft)

    public companion object {
        /**
         * The form of [typeName] in [schema]. Nested forms go [depthLimit] levels deep in all; below that a nested form
         * has no fields.
         *
         * @throws IllegalArgumentException if [depthLimit] is less than 1.
         */
        public fun of(typeName: String, schema: TypeSchema, depthLimit: Int = DEFAULT_DEPTH_LIMIT): PayloadForm {
            require(depthLimit >= 1) { "a form has at least one level, not $depthLimit" }
            val type = schema.type(typeName) ?: return PayloadForm(typeName, emptyList())
            val scalars = type.properties.map { PayloadField.Scalar(it.key, it.type, it.isList) }
            val nested = type.relations.map { relation ->
                val form: () -> PayloadForm = if (depthLimit > 1) {
                    { of(relation.targetType, schema, depthLimit - 1) }
                } else {
                    { PayloadForm(relation.targetType, emptyList()) }
                }
                PayloadField.Nested(relation.key, relation.targetType, relation.association, relation.isList, form)
            }
            return PayloadForm(type.name, scalars + nested)
        }

        /** The default depth of a form and its nested forms. */
        public const val DEFAULT_DEPTH_LIMIT: Int = 4
    }
}

/** A value of a simple property in a draft; `events` keeps its own values, independent of the mirror's. */
public sealed interface PayloadScalar {
    /**
     * A number.
     *
     * @property value the number.
     */
    public data class Number(public val value: Double) : PayloadScalar

    /**
     * A string.
     *
     * @property value the text.
     */
    public data class Text(public val value: String) : PayloadScalar

    /**
     * A boolean.
     *
     * @property value the truth value.
     */
    public data class Flag(public val value: Boolean) : PayloadScalar
}

/** The value of one field of a draft. */
public sealed interface PayloadDraftValue {
    /**
     * A single value.
     *
     * @property value the value.
     */
    public data class Single(public val value: PayloadScalar) : PayloadDraftValue

    /**
     * The values of a list.
     *
     * @property values the values in order.
     */
    public data class Many(public val values: List<PayloadScalar>) : PayloadDraftValue
}

/**
 * The values a user filled into a [PayloadForm], by path: the keys from the payload object down to the simple property,
 * with the decimal index of the entry after the key of a list relation (`["destination", "x"]`,
 * `["stops", "0", "x"]`). Immutable.
 *
 * @property values the values by path.
 */
public data class PayloadDraft(public val values: Map<List<String>, PayloadDraftValue>) {
    public companion object {
        /** The draft without values. */
        public val EMPTY: PayloadDraft = PayloadDraft(emptyMap())
    }
}
