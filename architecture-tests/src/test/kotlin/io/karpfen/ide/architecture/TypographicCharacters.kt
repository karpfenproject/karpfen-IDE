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
package io.karpfen.ide.architecture

/**
 * A typographic character found in documentation text.
 *
 * @property char the character.
 * @property offset the offset of the character in the text that was searched.
 */
internal data class TypographicCharacter(val char: Char, val offset: Int) {

    /** The code point and the name of [char], for failure messages, for example `U+2014 em dash`. */
    val description: String
        get() = "U+%04X %s".format(char.code, TypographicCharacters.nameOf(char))
}

/**
 * Recognizes the typographic characters that documentation does without: the em dash U+2014, the en dash U+2013, the
 * middle dot U+00B7, the bullet U+2022 and the arrows of the Unicode block U+2190 to U+21FF.
 *
 * Text that leans on these characters reads like generated text. A dash glues clauses together that read better as
 * separate sentences, a middle dot or a bullet replaces a list or a sentence, and an arrow replaces the verb that says
 * what happens. So documentation is written in plain sentences with plain punctuation: a hyphen joins the parts of a
 * word, a comma or a full stop separates clauses, and a verb such as "becomes", "maps to" or "returns" says what an
 * arrow would only hint at. The characters are named here by their code points, so this file holds none of them.
 *
 * Other characters stay allowed, among them the hyphen, the ASCII arrows of code in backticks, the ellipsis, the
 * section sign and typographic quotes. The section sign is a matter of [ReferenceIds].
 */
internal object TypographicCharacters {

    /** The single characters that documentation does without, by their names. */
    private val NAMED: Map<Char, String> = mapOf(
        '\u2014' to "em dash",
        '\u2013' to "en dash",
        '\u00B7' to "middle dot",
        '\u2022' to "bullet",
    )

    /** The Unicode block of arrows, all of which documentation does without. */
    private val ARROWS: CharRange = '\u2190'..'\u21FF'

    /** The typographic characters in [text], ordered by their offset. */
    fun findIn(text: String): List<TypographicCharacter> =
        text.indices.filter { isTypographic(text[it]) }.map { TypographicCharacter(text[it], it) }

    /** The name of [char], one of the characters this object recognizes, or "arrow" for an arrow. */
    fun nameOf(char: Char): String = NAMED[char] ?: "arrow"

    /** True if documentation does without [char]. */
    private fun isTypographic(char: Char): Boolean = char in NAMED || char in ARROWS
}
