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
package io.karpfen.ide.core.text

import kotlin.random.Random

/** Generates random edits for property tests; deterministic for a given [random]. */
class RandomEdits(private val random: Random) {

    /** Random short text including newlines, whitespace, punctuation and a surrogate pair. */
    fun text(maxLength: Int = 6): String = buildString {
        repeat(random.nextInt(maxLength + 1)) { append(ALPHABET[random.nextInt(ALPHABET.size)]) }
    }

    /** One random character of the alphabet (one code point). */
    fun character(): String = ALPHABET[random.nextInt(ALPHABET.size)]

    /** A random offset in `0..length`. */
    fun offset(length: Int): Int = random.nextInt(length + 1)

    /** A random range inside a text of [length]. */
    fun range(length: Int): TextRange {
        val a = offset(length)
        val b = offset(length)
        return TextRange(minOf(a, b), maxOf(a, b))
    }

    /**
     * 1–[maxEdits] valid edits for a text of [length], in random order: insertions (sometimes several at one offset),
     * deletions and replacements, occasionally a no-op. Deletions dominate when the text is longer than [softLimit].
     */
    fun edits(length: Int, maxEdits: Int = 4, softLimit: Int = 400): List<TextEdit> {
        val count = 1 + random.nextInt(maxEdits)
        val points = List(2 * count) { offset(length) }.sorted()
        val edits = ArrayList<TextEdit>()
        for (index in 0 until count) {
            val start = points[2 * index]
            val end = points[2 * index + 1]
            val shrink = length > softLimit
            edits += when (random.nextInt(if (shrink) 3 else 6)) {
                0 -> TextEdit.delete(start, end)
                1 -> TextEdit.replace(TextRange(start, end), text(if (shrink) 1 else 4))
                2 -> TextEdit.insert(start, "")
                else -> TextEdit.insert(start, text(5).ifEmpty { "x" })
            }
            if (!shrink && random.nextInt(8) == 0) edits += TextEdit.insert(start, text(3).ifEmpty { "y" })
        }
        return edits.shuffled(random)
    }

    private companion object {
        val ALPHABET = listOf("a", "b", "c", "_", "1", " ", " ", "\t", "\n", "\n", "{", "}", "(", ")", ";", "ä", "😀")
    }
}
