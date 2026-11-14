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

/** The text of the member [name] if it is a string, else null. */
internal fun WireValue.WireObject.stringMember(name: String): String? = (this[name] as? WireValue.WireString)?.value

/**
 * The member [name] as a string or null: [NullableText.Present] with the text of a string or with null for a JSON
 * `null`, or [NullableText.Absent] if the member is missing or of another type.
 */
internal fun WireValue.WireObject.nullableStringMember(name: String): NullableText =
    when (val value = this[name]) {
        is WireValue.WireString -> NullableText.Present(value.value)
        WireValue.WireNull -> NullableText.Present(null)
        else -> NullableText.Absent
    }

/** What [nullableStringMember] found. */
internal sealed interface NullableText {
    /**
     * The member is a string or `null`.
     *
     * @property text the text, or null for `null`.
     */
    data class Present(val text: String?) : NullableText

    /** The member is missing or neither a string nor `null`. */
    data object Absent : NullableText
}

/** The member [name] if it is an object, else null. */
internal fun WireValue.WireObject.objectMember(name: String): WireValue.WireObject? = this[name] as? WireValue.WireObject

/** The member [name] as a whole number if it is an integral number within the range of [Long], else null. */
internal fun WireValue.WireObject.integralMember(name: String): Long? = (this[name] as? WireValue.WireNumber)?.value?.toWholeNumber()

/** The texts of the member [name] if it is an array of strings only, in order, else null. */
internal fun WireValue.WireObject.stringsMember(name: String): List<String>? = (this[name] as? WireValue.WireArray)?.strings()

/** The texts of this array if every item is a string, in order, else null. */
internal fun WireValue.WireArray.strings(): List<String>? =
    items.map { (it as? WireValue.WireString)?.value ?: return null }

/** True if this object has a string `__id__` and a string `__type__`, as every object the runtime writes has. */
internal fun WireValue.WireObject.isRuntimeObject(): Boolean =
    stringMember(WireNames.OBJECT_ID_MEMBER) != null &&
        stringMember(WireNames.TYPE_MEMBER) != null

/** The first [ProtocolViolation.EXCERPT_LENGTH] characters of this text, the excerpt a violation keeps. */
internal fun String.excerpt(): String = take(ProtocolViolation.EXCERPT_LENGTH)

/** This number as a [Long] if it is finite, integral and within the range of [Long], else null. */
private fun Double.toWholeNumber(): Long? =
    if (isFinite() && this == Math.rint(this) && this >= Long.MIN_VALUE.toDouble() && this < Long.MAX_VALUE.toDouble()) {
        toLong()
    } else {
        null
    }
