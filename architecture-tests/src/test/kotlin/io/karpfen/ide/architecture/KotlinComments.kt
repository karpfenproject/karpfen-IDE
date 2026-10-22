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
 * @property comment the comment.
 * @property offset the offset of its first character in the source text.
 * @property block the kind of brace block it stands in.
 * @property parenDepth the number of parentheses open around it in that block.
 */
internal data class PlacedComment(val comment: SourceComment, val offset: Int, val block: BlockKind, val parenDepth: Int)

/**
 * Finds the comments in Kotlin source text, in `.kt` files and in Gradle's `.kts` scripts alike.
 *
 * Only a lexer can tell a comment from text that merely looks like one, such as a URL in a string, so this object
 * follows the lexical rules of Kotlin as far as comments depend on them:
 *
 * - A line comment runs from two slashes to the end of the line, a block comment from a slash and an asterisk to the
 *   matching asterisk and slash. Block comments nest, KDoc included, so a nested opening delimiter needs its own
 *   closing one. A block comment whose opening delimiter has a second asterisk is KDoc, except the empty `/**/`.
 * - Nothing inside a string literal is a comment. A regular string ends at the next unescaped quote or, unterminated,
 *   at the end of the line; a backslash escapes the next character. A raw string has no escapes and ends at the last
 *   three of a run of three or more quotes, so a quote right before its closing delimiter belongs to the content.
 * - A string template expression, `${` up to the brace that matches it, is code again inside either kind of string:
 *   it may hold comments, nested strings and braces of lambdas, which the scanner follows to any depth. A simple
 *   template such as `$name` holds an identifier only and needs no special case.
 * - Character literals and identifiers in backticks are skipped, so `'"'` opens no string and a test name such as
 *   `` `a // b` `` holds no comment.
 *
 * An unterminated comment, string, character literal or backtick identifier ends with the text instead of failing,
 * because the compiler already rejects such a file and the scanner only has to terminate. The code between them, its
 * words, parentheses, braces and semicolons, goes to [CodeBlocks], which tells each comment the block it stands in.
 */
internal object KotlinComments {

    /** The comments of [source] in the order in which they appear. */
    fun scan(source: String): List<SourceComment> = scanPlaced(source).map { it.comment }

    /** The comments of [source] with their places, in the order in which they appear. */
    fun scanPlaced(source: String): List<PlacedComment> = Scan(source).comments()

    /**
     * One pass over [source]. Code and string bodies are scanned by two mutually recursive functions, so the nesting
     * of string templates inside strings inside template expressions is kept on the call stack.
     */
    private class Scan(private val source: String) {

        /** The comments found so far, in the order of their offsets. */
        private val found = mutableListOf<PlacedComment>()

        /** The brace blocks of the code read so far. */
        private val blocks = CodeBlocks()

        /** The offset of the next character to read. */
        private var position = 0

        /** The offsets of the line breaks of [source], to turn the offset of a comment into its line. */
        private val lineBreaks: IntArray = source.indices.filter { source[it] == '\n' }.toIntArray()

        /** Scans all of [source] as code and returns the comments it found. */
        fun comments(): List<PlacedComment> {
            scanCode(insideTemplate = false)
            return found
        }

        /**
         * Scans code from [position]. Inside a template expression ([insideTemplate]) the scan ends just past the
         * closing brace that matches the template's opening one; braces in between, such as those of a lambda, are
         * counted so they do not end it early. At the top level it runs to the end of the text.
         */
        private fun scanCode(insideTemplate: Boolean) {
            var openBraces = 0
            while (position < source.length) {
                val char = source[position]
                when {
                    source.startsWith("//", position) -> recordComment(CommentKind.LINE, endOfLine(position))
                    source.startsWith("/*", position) -> recordComment(blockKind(position), endOfBlockComment(position))
                    source.startsWith("\"\"\"", position) -> {
                        position += 3
                        scanStringBody(raw = true)
                    }
                    char == '"' -> {
                        position++
                        scanStringBody(raw = false)
                    }
                    char == '\'' || char == '`' -> position = endOfQuoted(position)
                    char == '{' -> {
                        openBraces++
                        blocks.openBrace()
                        position++
                    }
                    char == '}' && insideTemplate && openBraces == 0 -> {
                        position++
                        return
                    }
                    char == '}' -> {
                        openBraces--
                        blocks.closeBrace()
                        position++
                    }
                    char.isLetter() || char == '_' -> readWord()
                    else -> {
                        readPunctuation(char)
                        position++
                    }
                }
            }
        }

        /**
         * Scans the body of a string literal whose opening delimiter has just been read and ends just past its closing
         * delimiter. A template expression inside the body is scanned as code. A [raw] string has no escapes and may
         * span lines; a regular string stops, unterminated, before a line break.
         */
        private fun scanStringBody(raw: Boolean) {
            while (position < source.length) {
                val char = source[position]
                when {
                    source.startsWith("\${", position) -> {
                        position += 2
                        scanCode(insideTemplate = true)
                    }
                    raw && source.startsWith("\"\"\"", position) -> {
                        position = endOfQuoteRun(position)
                        return
                    }
                    raw -> position++
                    char == '\\' -> position += 2
                    char == '"' -> {
                        position++
                        return
                    }
                    char == '\n' -> return
                    else -> position++
                }
            }
            position = minOf(position, source.length)
        }

        /** Records the comment that spans from [position] to [end] and continues after it. */
        private fun recordComment(kind: CommentKind, end: Int) {
            val comment = SourceComment(kind, lineOf(position), source.substring(position, end))
            found += PlacedComment(comment, position, blocks.current, blocks.parenDepth)
            position = end
        }

        /** Reads the identifier or keyword at [position] and hands it to [blocks]. */
        private fun readWord() {
            val start = position
            while (position < source.length && (source[position].isLetterOrDigit() || source[position] == '_')) position++
            blocks.word(source.substring(start, position), isMemberReference = source.startsWith("::", start - 2))
        }

        /** Hands the parentheses and semicolons of the code to [blocks]. */
        private fun readPunctuation(char: Char) {
            when (char) {
                '(' -> blocks.openParen()
                ')' -> blocks.closeParen()
                ';' -> blocks.semicolon()
            }
        }

        /** KDoc if the block comment at [start] opens with two asterisks and is not the empty comment `/**/`. */
        private fun blockKind(start: Int): CommentKind =
            if (source.startsWith("/**", start) && !source.startsWith("/**/", start)) CommentKind.KDOC else CommentKind.BLOCK

        /** The offset of the line break that ends the line holding [start], or the end of the text. */
        private fun endOfLine(start: Int): Int = source.indexOf('\n', start).let { if (it < 0) source.length else it }

        /**
         * The offset just past the block comment that opens at [start]. Every nested opening delimiter is closed before
         * the comment itself; an unterminated comment runs to the end of the text.
         */
        private fun endOfBlockComment(start: Int): Int {
            var depth = 0
            var offset = start
            while (offset < source.length) {
                when {
                    source.startsWith("/*", offset) -> {
                        depth++
                        offset += 2
                    }
                    source.startsWith("*/", offset) -> {
                        depth--
                        offset += 2
                        if (depth == 0) return offset
                    }
                    else -> offset++
                }
            }
            return source.length
        }

        /**
         * The offset just past the run of three or more quotes at [start]. The last three quotes of the run close the
         * raw string; those before them are part of its content.
         */
        private fun endOfQuoteRun(start: Int): Int {
            var offset = start
            while (offset < source.length && source[offset] == '"') offset++
            return offset
        }

        /**
         * The offset just past the character literal or backtick identifier that opens at [start]: the next unescaped
         * occurrence of its opening character. A backslash escapes the next character; neither form spans lines.
         */
        private fun endOfQuoted(start: Int): Int {
            val delimiter = source[start]
            var offset = start + 1
            while (offset < source.length && source[offset] != delimiter && source[offset] != '\n') {
                offset += if (source[offset] == '\\' && delimiter == '\'') 2 else 1
            }
            return if (offset < source.length && source[offset] == delimiter) offset + 1 else minOf(offset, source.length)
        }

        /** The 1-based line of the character at [offset]: one more than the number of line breaks before it. */
        private fun lineOf(offset: Int): Int {
            val index = lineBreaks.binarySearch(offset)
            return (if (index >= 0) index else -index - 1) + 1
        }
    }

    /** The 1-based line of the character at [offset]: one more than the number of [lineBreaks] before it. */
    private fun lineOf(offset: Int, lineBreaks: IntArray): Int {
        val index = lineBreaks.binarySearch(offset)
        return (if (index >= 0) index else -index - 1) + 1
    }
}
