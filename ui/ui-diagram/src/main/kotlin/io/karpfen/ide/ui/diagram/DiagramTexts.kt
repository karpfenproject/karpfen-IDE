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
package io.karpfen.ide.ui.diagram

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

/** What a text of a diagram is, which decides the style it is written in. */
internal enum class TextRole {
    /** The name at the top of a box, such as the name of a state or a type. */
    TITLE,

    /** A line below the title of a box, such as a property of a type. */
    ROW,

    /** The caption of a line, such as the condition of a transition. */
    LABEL,
}

/**
 * The styles the texts of a diagram are written in.
 *
 * @property title the style of a [TextRole.TITLE].
 * @property row the style of a [TextRole.ROW].
 * @property label the style of a [TextRole.LABEL].
 */
internal data class DiagramTextStyles(val title: TextStyle, val row: TextStyle, val label: TextStyle) {
    /** The style of a text with the role [role]. */
    fun of(role: TextRole): TextStyle = when (role) {
        TextRole.TITLE -> title
        TextRole.ROW -> row
        TextRole.LABEL -> label
    }
}

/**
 * The measured texts of a diagram, in pixels. The layout needs the widths, and the drawing needs the laid-out texts
 * themselves. A text is found by its role and its wording.
 */
internal class DiagramTexts(private val measured: Map<TextRole, Map<String, TextLayoutResult>>) {

    /** The laid-out [text] in the style of [role], or null if it was not measured. */
    operator fun get(role: TextRole, text: String): TextLayoutResult? = measured[role]?.get(text)

    /**
     * The width of [text] in pixels. Where the same wording was measured in several roles, the widest one counts, so
     * the room is enough for each. A text that was not measured is guessed from its length.
     */
    fun widthOf(text: String): Float =
        measured.values.mapNotNull { it[text]?.size?.width?.toFloat() }.maxOrNull() ?: (text.length * GUESSED_CHARACTER_WIDTH)

    companion object {
        private const val GUESSED_CHARACTER_WIDTH = 7f

        /** Measures [texts], the wordings by their role, with [measurer] in the [styles] of their roles. */
        fun measure(texts: Map<TextRole, Collection<String>>, measurer: TextMeasurer, styles: DiagramTextStyles): DiagramTexts =
            DiagramTexts(
                texts.mapValues { (role, wordings) -> wordings.distinct().associateWith { measurer.measure(it, styles.of(role)) } },
            )
    }
}
