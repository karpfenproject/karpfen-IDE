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

import io.karpfen.ide.runtime.protocol.message.WireValue

/**
 * A path into a JSON value, in the usual notation: `$` is the whole value, `$.a.b` the member `b` of the member `a`,
 * and `$.a[0]` the first element of the array `a`. A route uses paths to pick the fields of an event's payload out of
 * the body of a message.
 *
 * @property text the path as it is written.
 */
public class JsonPath private constructor(public val text: String, private val steps: List<Step>) {

    /** One step of a path: into a member by its name, or into an array by its index. */
    private sealed interface Step {
        data class Member(val name: String) : Step

        data class Element(val index: Int) : Step
    }

    /** The value this path leads to in [root], or null if [root] has no such place. */
    public fun read(root: WireValue): WireValue? {
        var current: WireValue = root
        for (step in steps) {
            current = when (step) {
                is Step.Member -> (current as? WireValue.WireObject)?.get(step.name)
                is Step.Element -> (current as? WireValue.WireArray)?.items?.getOrNull(step.index)
            } ?: return null
        }
        return current
    }

    override fun equals(other: Any?): Boolean = other is JsonPath && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    public companion object {
        private val STEP = Regex("""\.([^.\[\]]+)|\[(\d+)]""")

        /** True if [text] looks like a path, which means it starts with `$`. */
        public fun looksLikePath(text: String): Boolean = text.startsWith("$")

        /** The path that [text] writes, or null if [text] is no path. */
        public fun parse(text: String): JsonPath? {
            val trimmed = text.trim()
            if (!looksLikePath(trimmed)) return null
            val steps = ArrayList<Step>()
            var position = 1
            while (position < trimmed.length) {
                val match = STEP.matchAt(trimmed, position) ?: return null
                steps += match.groups[1]?.let { Step.Member(it.value) } ?: Step.Element(match.groupValues[2].toInt())
                position = match.range.last + 1
            }
            return JsonPath(trimmed, steps)
        }
    }
}
