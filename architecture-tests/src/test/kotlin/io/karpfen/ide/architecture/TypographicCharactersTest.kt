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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [TypographicCharacters], the recognizer of the characters that documentation does without. It finds each of
 * them, the first and the last arrow of the arrow block included, and leaves plain punctuation and the characters
 * next to the arrow block alone. The inputs write every such character as an escape, so this file holds none of them.
 */
class TypographicCharactersTest {

    /** The characters that [TypographicCharacters] finds in [text]. */
    private fun charactersIn(text: String): List<Char> = TypographicCharacters.findIn(text).map { it.char }

    @Test
    fun `finds dashes, the middle dot, the bullet and every arrow`() {
        val text = "a \u2014 b \u2013 c \u00B7 d \u2022 e \u2190 f \u2192 g \u21D2 h \u21FF"
        assertEquals(listOf('\u2014', '\u2013', '\u00B7', '\u2022', '\u2190', '\u2192', '\u21D2', '\u21FF'), charactersIn(text))
    }

    @Test
    fun `plain punctuation and the characters around the arrow block are allowed`() {
        listOf(
            "a hyphen-joined word, a comma; a colon: and a full stop.",
            "the ASCII arrows `prop(\"x\") -> \"1\"` and `a => b`, the ellipsis `Live\u2026Test`",
            "the section sign \u00A7 and \u201Ctypographic quotes\u201D",
            "the minus sign \u2212, the character before the arrows \u218F and the one after them \u2200",
        ).forEach { text -> assertEquals(emptyList(), charactersIn(text), text) }
    }

    @Test
    fun `characters are ordered by their offset and described by code point and name`() {
        val found = TypographicCharacters.findIn("x\u2192y\u2014z")

        assertEquals(listOf(TypographicCharacter('\u2192', 1), TypographicCharacter('\u2014', 3)), found)
        assertEquals(listOf("U+2192 arrow", "U+2014 em dash"), found.map { it.description })
    }
}
