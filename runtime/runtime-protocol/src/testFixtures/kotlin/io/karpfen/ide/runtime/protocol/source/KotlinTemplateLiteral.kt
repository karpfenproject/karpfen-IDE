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
package io.karpfen.ide.runtime.protocol.source

/**
 * A string template of a runtime Kotlin file, quoted (`"…"`) or triple-quoted (`"""…"""`), split into its literal text
 * and its placeholders `$name` and `${expression}`, so a test can fill the placeholders with sample values and compare
 * the result with a golden: the runtime's own template, interpolated, must write the golden's bytes.
 *
 * The literal text of a quoted string is unescaped as Kotlin does (`\t`, `\b`, `\n`, `\r`, `\'`, `\"`, `\\`, `\$` and
 * `\uXXXX`); a triple-quoted string is taken as written, including the quotes Kotlin adds to its content before the
 * closing delimiter.
 *
 * A template may also be a concatenation of literals joined by `+`, such as a long template written over several lines
 * ([concatenationStartingWith]); its parts are then the parts of its literals in order.
 *
 * @property written the literal as written in the file, from its opening to its closing delimiter, or the whole
 *   concatenation.
 * @param parts the literal text and the placeholders in order.
 * @param range where the literal stands in the file.
 */
public class KotlinTemplateLiteral private constructor(
    public val written: String,
    private val parts: List<TemplatePart>,
    private val range: IntRange,
) {

    /** The placeholders in order: the name of a `$name`, the expression of a `${expression}` as written. */
    public val placeholders: List<String>
        get() = parts.filterIsInstance<TemplatePart.Placeholder>().map { it.expression }

    /** The text as written after the opening delimiter of the first literal. */
    public val content: String
        get() = if (written.startsWith(TRIPLE_QUOTE)) written.removePrefix(TRIPLE_QUOTE) else written.removePrefix(QUOTE)

    /**
     * The text the template produces when every placeholder is replaced by its value in [samples], keyed by the name or
     * the expression as written.
     *
     * @throws IllegalArgumentException naming the placeholder if [samples] has no value for it.
     */
    public fun interpolate(samples: Map<String, String>): String =
        parts.joinToString("") { part ->
            when (part) {
                is TemplatePart.Text -> part.text
                is TemplatePart.Placeholder -> requireNotNull(samples[part.expression]) {
                    "no sample value for the placeholder '${part.expression}' of $written"
                }
            }
        }

    override fun toString(): String = written

    public companion object {
        /** The delimiter of a triple-quoted string. */
        private const val TRIPLE_QUOTE = "\"\"\""

        /** The delimiter of a quoted string. */
        private const val QUOTE = "\""

        /** Every string literal of the Kotlin source [text] that stands in code, in order; nested templates excluded. */
        public fun all(text: String): List<KotlinTemplateLiteral> = KotlinStringScanner(text).literals()

        /**
         * The one string literal of [text] whose content as written starts with [prefix], joined with the literals that
         * follow it with `+`: a template written as a concatenation of literals over several lines.
         *
         * @throws IllegalArgumentException if no literal or more than one starts so.
         */
        public fun concatenationStartingWith(text: String, prefix: String): KotlinTemplateLiteral {
            val literals = all(text)
            var joined = startingWith(text, prefix)
            var next = literals.indexOfFirst { it.range == joined.range } + 1
            while (next < literals.size && CONCATENATION.matches(text.substring(joined.range.last + 1, literals[next].range.first))) {
                val following = literals[next]
                joined = KotlinTemplateLiteral(
                    text.substring(joined.range.first, following.range.last + 1),
                    joined.parts + following.parts,
                    joined.range.first..following.range.last,
                )
                next++
            }
            return joined
        }

        /** What stands between two literals of a concatenation: a plus and blanks. */
        private val CONCATENATION = Regex("""\s*\+\s*""")

        /**
         * The one string literal of [text] whose content as written starts with [prefix], such as
         * `{"objectId":${ModelJsonWriter.quote(objectId)},"revision"`.
         *
         * @throws IllegalArgumentException if no literal or more than one starts so.
         */
        public fun startingWith(text: String, prefix: String): KotlinTemplateLiteral {
            val found = all(text).filter { it.content.startsWith(prefix) }
            require(found.size == 1) { "expected one string literal starting with $prefix, found ${found.size}: $found" }
            return found.single()
        }

        /** The literal at [range] of a file, written as [written], with [parts]; for the scanner. */
        internal fun of(written: String, parts: List<TemplatePart>, range: IntRange): KotlinTemplateLiteral =
            KotlinTemplateLiteral(written, parts, range)
    }
}

/** A piece of a [KotlinTemplateLiteral]. */
internal sealed interface TemplatePart {
    /** Literal text, unescaped. */
    data class Text(val text: String) : TemplatePart

    /** A placeholder: a name or an expression as written. */
    data class Placeholder(val expression: String) : TemplatePart
}

/**
 * Finds the string literals of a Kotlin source: skips line and block comments and character literals, reads quoted and
 * triple-quoted strings with their placeholders, and skips the strings nested in a placeholder's expression.
 *
 * @param text the Kotlin source.
 */
private class KotlinStringScanner(private val text: String) {

    /** The position of the scanner. */
    private var position = 0

    /** The literals that stand in code, in order. */
    fun literals(): List<KotlinTemplateLiteral> {
        val found = ArrayList<KotlinTemplateLiteral>()
        while (position < text.length) {
            when {
                at(SLASH, SLASH) -> skipLineComment()
                at(SLASH, STAR) -> skipBlockComment()
                text[position] == APOSTROPHE -> skipCharLiteral()
                text[position] == QUOTE -> found += readString()
                else -> position++
            }
        }
        return found
    }

    /** True if the text at the position starts with [first] and [second]. */
    private fun at(first: Char, second: Char): Boolean =
        position + 1 < text.length && text[position] == first && text[position + 1] == second

    /** Skips a comment up to the end of its line. */
    private fun skipLineComment() {
        while (position < text.length && text[position] != '\n') position++
    }

    /** Skips a block comment, with the comments nested in it. */
    private fun skipBlockComment() {
        var depth = 0
        do {
            when {
                at(SLASH, STAR) -> {
                    depth++
                    position += 2
                }
                at(STAR, SLASH) -> {
                    depth--
                    position += 2
                }
                else -> position++
            }
        } while (depth > 0 && position < text.length)
    }

    /** Skips a character literal such as `'"'` or `'A'`. */
    private fun skipCharLiteral() {
        position++
        if (text[position] == BACKSLASH) {
            position += if (text[position + 1] == 'u') UNICODE_ESCAPE_LENGTH else 2
        } else {
            position++
        }
        check(text[position] == APOSTROPHE) { "a character literal does not end where expected at $position" }
        position++
    }

    /** Reads the string literal at the position, quoted or triple-quoted. */
    private fun readString(): KotlinTemplateLiteral {
        val start = position
        val raw = text.startsWith(TRIPLE, position)
        position += if (raw) TRIPLE.length else 1
        val parts = ArrayList<TemplatePart>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) parts += TemplatePart.Text(literal.toString())
            literal.clear()
        }
        while (true) {
            check(position < text.length) { "a string literal starting at $start does not end" }
            val char = text[position]
            when {
                raw && text.startsWith(TRIPLE, position) -> {
                    if (position + TRIPLE.length < text.length && text[position + TRIPLE.length] == QUOTE) {
                        literal.append(QUOTE)
                        position++
                    } else {
                        position += TRIPLE.length
                        break
                    }
                }
                !raw && char == QUOTE -> {
                    position++
                    break
                }
                !raw && char == BACKSLASH -> literal.append(readEscape())
                char == DOLLAR && text.getOrNull(position + 1) == '{' -> {
                    flush()
                    parts += TemplatePart.Placeholder(readExpression())
                }
                char == DOLLAR && text.getOrNull(position + 1)?.let { it.isLetter() || it == '_' } == true -> {
                    flush()
                    parts += TemplatePart.Placeholder(readName())
                }
                else -> {
                    literal.append(char)
                    position++
                }
            }
        }
        flush()
        return KotlinTemplateLiteral.of(text.substring(start, position), parts, start until position)
    }

    /** Reads an escape of a quoted string at the position and answers the character it stands for. */
    private fun readEscape(): Char {
        val escaped = text[position + 1]
        position += 2
        return when (escaped) {
            't' -> '\t'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            'u' -> text.substring(position, position + HEX_DIGITS).toInt(HEX_RADIX).toChar().also { position += HEX_DIGITS }
            else -> escaped
        }
    }

    /** Reads the expression of a `${…}` placeholder at the position, skipping the strings and braces nested in it. */
    private fun readExpression(): String {
        position += 2
        val start = position
        var depth = 1
        while (depth > 0) {
            check(position < text.length) { "a template expression starting at $start does not end" }
            when (text[position]) {
                QUOTE -> readString()
                APOSTROPHE -> skipCharLiteral()
                '{' -> {
                    depth++
                    position++
                }
                '}' -> {
                    depth--
                    position++
                }
                else -> position++
            }
        }
        return text.substring(start, position - 1)
    }

    /** Reads the name of a `$name` placeholder at the position. */
    private fun readName(): String {
        position++
        val start = position
        while (position < text.length && (text[position].isLetterOrDigit() || text[position] == '_')) position++
        return text.substring(start, position)
    }

    private companion object {
        /** Starts and ends comments, with [STAR]. */
        const val SLASH = '/'

        /** Starts and ends block comments, with [SLASH]. */
        const val STAR = '*'

        /** Delimits strings. */
        const val QUOTE = '"'

        /** Delimits character literals. */
        const val APOSTROPHE = '\''

        /** Starts an escape. */
        const val BACKSLASH = '\\'

        /** Starts a placeholder. */
        const val DOLLAR = '$'

        /** Delimits triple-quoted strings. */
        const val TRIPLE = "\"\"\""

        /** The length of a character literal's unicode escape, the backslash, `u` and four digits. */
        const val UNICODE_ESCAPE_LENGTH = 6

        /** The digits of a unicode escape. */
        const val HEX_DIGITS = 4

        /** The radix of a unicode escape. */
        const val HEX_RADIX = 16
    }
}
