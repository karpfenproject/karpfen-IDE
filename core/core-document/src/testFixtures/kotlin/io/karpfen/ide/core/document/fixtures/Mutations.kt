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
package io.karpfen.ide.core.document.fixtures

import kotlin.random.Random

/**
 * A deterministically mutated variant of a text, for the robustness tests. Tokenizers, parsers and language services
 * must never throw on any mutation of the examples.
 *
 * @property index the position in the generated sequence, counted from 0.
 * @property operations a readable description of each applied operation in order, for failure messages.
 * @property text the mutated text.
 */
public data class Mutation(public val index: Int, public val operations: List<String>, public val text: String) {
    override fun toString(): String = "mutation #$index: ${operations.joinToString("; ")}"
}

/**
 * Deterministic random mutations of source texts. A mutation deletes ranges, inserts grammar punctuation, keywords
 * and tricky characters, or truncates the text. The same `(text, seed, count)` always yields the same mutations.
 */
public object Mutations {

    /**
     * Punctuation and operators of the three grammars, EVAL markers and characters that stress offset handling. These
     * characters are line separators and blanks, `ä` and `€` with one UTF-16 unit each, the surrogate pair `😀` of
     * U+1F600 and the unpaired high surrogate `\uD83D`.
     */
    public val PUNCTUATION: List<String> = listOf(
        "{", "}", "(", ")", ",", ":", "\"", "'", "->", "$(", "@", "#", "//", "/*", "\\", ";", "=",
        "\n", "\t", " ", "\r", "ä", "€", "😀",
        "\uD83D",
    )

    /** Keywords and operations of KMeta, KModel and KStates. */
    public val KEYWORDS: List<String> = listOf(
        "type", "prop", "has", "knows", "list", "make", "object",
        "STATEMACHINE", "ATTACHED", "TO", "STATES", "STATE", "INITIAL", "ENTRY", "DO", "TRANSITIONS", "TRANSITION",
        "SPLIT", "JOIN", "NOT", "LOOPING", "CONDITION", "VALUE", "EVAL", "MACRO", "MACROS", "TAKES", "RETURNS",
        "DEFINITION", "reference", "IF", "IN", "SCOPE", "WITH", "AS", "with", "as",
        "SET", "APPEND", "SETLIST", "DROPLIST", "SETOBJ", "APPENDOBJ", "DROPOBJ", "EVENT", "set", "append", "dropobj",
    )

    /** Larger fragments that open or close constructs. */
    public val FRAGMENTS: List<String> = listOf(
        "\"x\"", "\"\"", "123", "1.5", "EVAL {", "EVAL { return $(a->b) }", "STATE \"s\" {", "type \"T\" \"d\" {",
        "make object \"k\":\"T\" {", "prop(\"p\") -> \"1\"", "MACRO(\"m\", \"a\")", "@m(", "$(self->", "// comment\n",
    )

    /** Everything [random] may insert. */
    public val INSERTIONS: List<String> = PUNCTUATION + KEYWORDS + FRAGMENTS

    /**
     * [count] mutations of [text]. Each applies one to three operations to the original [text]. An operation deletes
     * a range of up to 40 characters with a chance of 45 %, inserts an element of [INSERTIONS] with 45 %, or truncates
     * with 10 %. The operations work on single characters, so surrogate pairs may be split on purpose.
     */
    public fun random(text: String, seed: Long, count: Int): List<Mutation> {
        require(count >= 0) { "count must be >= 0, was $count" }
        val random = Random(seed)
        return List(count) { index ->
            val operations = ArrayList<String>()
            var current = text
            repeat(1 + random.nextInt(3)) {
                val roll = random.nextInt(100)
                current = when {
                    current.isEmpty() || roll in 45 until 90 -> {
                        val offset = random.nextInt(current.length + 1)
                        val insertion = INSERTIONS[random.nextInt(INSERTIONS.size)]
                        operations += "insert ${quote(insertion)} at $offset"
                        current.substring(0, offset) + insertion + current.substring(offset)
                    }
                    roll < 45 -> {
                        val start = random.nextInt(current.length)
                        val end = minOf(current.length, start + 1 + random.nextInt(40))
                        operations += "delete [$start, $end)"
                        current.removeRange(start, end)
                    }
                    else -> {
                        val offset = random.nextInt(current.length)
                        operations += "truncate at $offset"
                        current.substring(0, offset)
                    }
                }
            }
            Mutation(index, operations, current)
        }
    }

    /** Every prefix of [text] with a length divisible by [step], from the empty string up to [text] itself. */
    public fun prefixes(text: String, step: Int = 1): Sequence<String> {
        require(step >= 1) { "step must be >= 1, was $step" }
        return (0..text.length step step).asSequence().map { text.substring(0, it) } +
            (if (text.length % step == 0) emptySequence() else sequenceOf(text))
    }

    /** [value] in quotes with line breaks, tabs and surrogates escaped, for readable operation descriptions. */
    private fun quote(value: String): String =
        "\"" + value.map { char ->
            when {
                char == '\n' -> "\\n"
                char == '\r' -> "\\r"
                char == '\t' -> "\\t"
                char.isSurrogate() -> "\\u%04X".format(char.code)
                else -> char.toString()
            }
        }.joinToString("") + "\""
}
