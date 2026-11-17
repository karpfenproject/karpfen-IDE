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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.core.text.TextRange

/**
 * Where the parts of one statement of a type or an object stand in the text. A rule of a type reads
 * `prop("key", "type")`, `has("key", "Type")` or `knows("key", "Type")`, with `list("type")` in place of the quoted type
 * for a list. A statement of an object reads `prop("key") -> "value"`, `knows("key") -> "target"` or
 * `has("key") -> make object ...`. The parts are found in the text the diagram was derived from, which is free of
 * syntax errors, so they are found by their place and not checked.
 *
 * @property word the keyword at the start: `prop`, `has` or `knows`.
 * @property key the key inside its quotes.
 * @property value the second string inside its quotes: the type of a rule, the value of a `prop` statement or the key
 *   a `knows` statement names. Null for a `has` statement of an object, whose second part is an object.
 * @property list the words `list(` up to the closing parenthesis around the quoted type of a rule that holds a list,
 *   or null for any other statement.
 */
internal class StatementParts private constructor(
    val word: TextRange,
    val key: TextRange,
    val value: TextRange?,
    val list: TextRange?,
) {
    companion object {
        private val STRING = Regex(""""([^"]*)"""")
        private val LIST = Regex("""\blist\s*\(""")

        /** The words that open a statement that makes an object, behind which the strings belong to that object. */
        private const val MAKE_OBJECT = "make object"

        /**
         * The parts of the statement at [range] of [text], or null if it does not start with a keyword and a quoted
         * key.
         */
        fun of(text: String, range: TextRange): StatementParts? {
            val statement = text.substring(range.start, range.end).substringBefore(MAKE_OBJECT)
            val wordLength = statement.takeWhile { it.isLetter() }.length.takeIf { it > 0 } ?: return null
            val strings = stringsIn(statement, range.start)
            val key = strings.firstOrNull() ?: return null
            val value = strings.getOrNull(1)
            val list = value?.let { listAround(text, key, it) }
            return StatementParts(TextRange(range.start, range.start + wordLength), key, value, list)
        }

        /** The insides of the string literals of [statement], which starts at [offset] of the text, in order. */
        fun stringsIn(statement: String, offset: Int): List<TextRange> =
            STRING.findAll(statement).map { TextRange(offset + it.range.first + 1, offset + it.range.last) }.toList()

        /**
         * The range from `list` to the closing parenthesis of `list("type")` around the quoted type [type], or null if
         * the type stands without `list` between the [key] and it.
         */
        private fun listAround(text: String, key: TextRange, type: TextRange): TextRange? {
            val between = text.substring(key.end + 1, type.start - 1)
            val opening = LIST.find(between) ?: return null
            val close = (type.end + 1 until text.length).firstOrNull { !text[it].isWhitespace() }?.takeIf { text[it] == ')' } ?: return null
            return TextRange(key.end + 1 + opening.range.first, close + 1)
        }
    }
}
