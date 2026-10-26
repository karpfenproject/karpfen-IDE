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
 * A comment found in Kotlin source text.
 *
 * @property kind whether it is a line comment, a plain block comment or KDoc.
 * @property line the 1-based line on which the comment starts.
 * @property text the whole comment, delimiters included.
 */
internal data class SourceComment(val kind: CommentKind, val line: Int, val text: String) {

    /** The 1-based line of the character at [offset] in [text], counted from the line on which the comment starts. */
    fun lineAt(offset: Int): Int = line + text.substring(0, offset).count { it == '\n' }

    /** The first line of [text], shortened to [maxLength] characters, for failure messages. */
    fun excerpt(maxLength: Int = 80): String {
        val firstLine = text.lineSequence().first().trim()
        return if (firstLine.length <= maxLength) firstLine else firstLine.take(maxLength - 1) + "…"
    }
}

/**
 * A comment with its place in the source text.
 *
 * @property offset the offset of its first character in the source text.
 * @property block the kind of brace block it stands in.
 * @property parenDepth the number of parentheses open around it in that block.
 */
internal data class PlacedComment(val comment: SourceComment, val offset: Int, val block: BlockKind, val parenDepth: Int)

/**
 * Finds the comments in Kotlin source text, in `.kt` files and in Gradle's `.kts` scripts alike. The comments are the
 * comment spans of [KotlinLexer], whose documentation lists the lexical rules that tell a comment from text that only
 * looks like one, such as a URL in a string. The code between them, its words, parentheses, braces and semicolons,
 * goes to [CodeBlocks], which tells each comment the block it stands in.
 */
internal object KotlinComments {

    /** The comments of [source] in the order in which they appear. */
    fun scan(source: String): List<SourceComment> = scanPlaced(source).map { it.comment }

    /** The comments of [source] with their places, in the order in which they appear. */
    fun scanPlaced(source: String): List<PlacedComment> = Placement(source).comments()

    /**
     * One pass over the code of [source], in which the lexer has blanked comments and literal text. The pass hands the
     * words and punctuation of the code to [CodeBlocks] and places each comment in the block reached at its start.
     * Since the delimiters of strings and of their template expressions are blanked too, every brace and parenthesis
     * the pass reads is code, while the code inside a template expression is read like any other.
     */
    private class Placement(private val source: String) {

        /** The comments and pieces of literal text of [source], in the order of their offsets. */
        private val spans: List<LexicalSpan> = KotlinLexer.spans(source)

        /** The code of [source], with every span of [spans] blanked. */
        private val code: String = KotlinLexer.code(source, spans)

        /** The brace blocks of the code read so far. */
        private val blocks = CodeBlocks()

        /** The offset of the next character of [code] to read. */
        private var position = 0

        /** The offsets of the line breaks of [source], to turn the offset of a comment into its line. */
        private val lineBreaks: IntArray = source.indices.filter { source[it] == '\n' }.toIntArray()

        /** Reads the code up to each comment span and returns the comments, each placed in its block. */
        fun comments(): List<PlacedComment> = spans.mapNotNull { span ->
            val kind = span.kind as? CommentKind ?: return@mapNotNull null
            readCodeUpTo(span.start)
            val comment = SourceComment(kind, lineOf(span.start), source.substring(span.start, span.end))
            PlacedComment(comment, span.start, blocks.current, blocks.parenDepth)
        }

        /** Reads [code] from [position] up to [end] and hands what it holds to [blocks]. */
        private fun readCodeUpTo(end: Int) {
            while (position < end) {
                val char = code[position]
                if (char.isLetter() || char == '_') {
                    readWord()
                } else {
                    readPunctuation(char)
                    position++
                }
            }
        }

        /** Reads the identifier or keyword at [position] and hands it to [blocks]. */
        private fun readWord() {
            val start = position
            while (position < code.length && (code[position].isLetterOrDigit() || code[position] == '_')) position++
            blocks.word(code.substring(start, position), isMemberReference = code.startsWith("::", start - 2))
        }

        /** Hands the braces, parentheses and semicolons of the code to [blocks]. */
        private fun readPunctuation(char: Char) {
            when (char) {
                '{' -> blocks.openBrace()
                '}' -> blocks.closeBrace()
                '(' -> blocks.openParen()
                ')' -> blocks.closeParen()
                ';' -> blocks.semicolon()
            }
        }

        /** The 1-based line of the character at [offset]: one more than the number of line breaks before it. */
        private fun lineOf(offset: Int): Int {
            val index = lineBreaks.binarySearch(offset)
            return (if (index >= 0) index else -index - 1) + 1
        }
    }
}
