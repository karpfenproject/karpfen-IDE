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
package io.karpfen.ide.core.lang.completion

import io.karpfen.ide.core.text.TextRange

/**
 * The text a proposal inserts, with the places the user fills in. It is an immutable value, built by a
 * [TemplateTextBuilder] or read from the marker syntax of a [Snippet] by [parse].
 *
 * @property text the inserted text without any marker.
 * @property placeholders the ranges of [text] the user fills in, in the order they are visited.
 * @property caret the offset in [text] where the caret goes at the end, or null for the end of [text].
 */
internal data class TemplateText(val text: String, val placeholders: List<TextRange>, val caret: Int?) {

    /** Reads the marker syntax of [Snippet.template]. */
    companion object {
        /**
         * Reads [template]. `$0` marks the caret at the end. `${n:word}` is the placeholder number `n` with the text
         * `word`, which may be empty and contains no `}`. Placeholders are visited by ascending number, and equal
         * numbers in the order they are written. A `$` that starts no marker, such as the `$(` of a path reference in
         * EVAL code, stays as it is written.
         */
        fun parse(template: String): TemplateText {
            val builder = TemplateTextBuilder()
            var i = 0
            while (i < template.length) {
                val marker = markerAt(template, i)
                when {
                    marker == null -> {
                        builder.literal(template[i].toString())
                        i++
                    }
                    marker.number == null -> {
                        builder.caret()
                        i = marker.end
                    }
                    else -> {
                        builder.placeholder(marker.text, marker.number)
                        i = marker.end
                    }
                }
            }
            return builder.build()
        }

        /** The marker that starts at [index] of [template], or null if none starts there. */
        private fun markerAt(template: String, index: Int): Marker? {
            if (!template.startsWith("$", index)) return null
            if (template.startsWith(CARET, index)) return Marker(null, "", index + CARET.length)
            if (!template.startsWith("\${", index)) return null
            val colon = template.indexOf(':', index + 2)
            val close = template.indexOf('}', index + 2)
            if (colon < 0 || close < colon) return null
            val number = template.substring(index + 2, colon).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt() ?: return null
            return Marker(number, template.substring(colon + 1, close), close + 1)
        }

        /** The caret marker. */
        const val CARET: String = "\$0"
    }

    /**
     * A marker of the template syntax.
     *
     * @property number the number of a placeholder, or null for the caret marker.
     * @property text the text of a placeholder.
     * @property end the offset right after the marker in the template.
     */
    private data class Marker(val number: Int?, val text: String, val end: Int)
}

/**
 * Builds a [TemplateText] piece by piece. Writers of generated templates, such as the KModel object templates, use it
 * directly, so the names they write never pass through the marker syntax. A builder is confined to one construction.
 */
internal class TemplateTextBuilder {
    private val text = StringBuilder()
    private val placeholders = ArrayList<Pair<Int, TextRange>>()
    private var caret: Int? = null

    /** Appends [literal] as it is. */
    fun literal(literal: String): TemplateTextBuilder = apply { text.append(literal) }

    /**
     * Appends [value] as a placeholder. It is visited in the order of [order], and placeholders of equal order in the
     * order they were appended. By default each placeholder is visited after the ones appended before it.
     */
    fun placeholder(value: String, order: Int = placeholders.size + 1): TemplateTextBuilder = apply {
        placeholders += order to TextRange.ofLength(text.length, value.length)
        text.append(value)
    }

    /** Marks the current end as the place of the caret after the last placeholder. */
    fun caret(): TemplateTextBuilder = apply { caret = text.length }

    /** The text built so far. */
    fun build(): TemplateText = TemplateText(text.toString(), placeholders.sortedBy { it.first }.map { it.second }, caret)
}
