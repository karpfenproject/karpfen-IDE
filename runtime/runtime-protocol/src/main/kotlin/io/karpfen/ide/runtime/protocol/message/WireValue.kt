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
 * A JSON value as the runtime wrote it, read by [RuntimeJson]. Objects keep their fields in the written order, repeated
 * names included. Every number is a [Double], as the runtime's numbers are. That includes the non-finite numbers the
 * runtime writes as bare `NaN`, `Infinity` and `-Infinity`. Values of this type are immutable.
 */
public sealed interface WireValue {
    /**
     * A JSON object.
     *
     * @property fields its members in the written order. A name may occur more than once.
     */
    public data class WireObject(public val fields: List<WireField>) : WireValue {
        /** The value of the first field named [name], or null if the object has none. */
        public operator fun get(name: String): WireValue? = fields.firstOrNull { it.name == name }?.value
    }

    /**
     * A JSON array.
     *
     * @property items its elements in order.
     */
    public data class WireArray(public val items: List<WireValue>) : WireValue

    /**
     * A JSON string.
     *
     * @property value its text, with every escape resolved.
     */
    public data class WireString(public val value: String) : WireValue

    /**
     * A JSON number, or one of the runtime's bare non-finite literals.
     *
     * @property value the number.
     */
    public data class WireNumber(public val value: Double) : WireValue

    /**
     * `true` or `false`.
     *
     * @property value the truth value.
     */
    public data class WireBoolean(public val value: Boolean) : WireValue

    /** `null`. */
    public data object WireNull : WireValue
}

/**
 * One member of a [WireValue.WireObject].
 *
 * @property name the member's name.
 * @property value the member's value.
 */
public data class WireField(public val name: String, public val value: WireValue)
