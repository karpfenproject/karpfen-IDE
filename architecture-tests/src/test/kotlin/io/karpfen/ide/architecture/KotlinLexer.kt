package io.karpfen.ide.architecture

/** What a [LexicalSpan] of Kotlin source text holds: a comment or literal text, never code. */
internal sealed interface SpanKind

/** The three kinds of comment in Kotlin source text. */
internal enum class CommentKind : SpanKind {
    /** A line comment, from two slashes to the end of the line. */
    LINE,

    /** A block comment that is not KDoc; the empty block comment `/**/` is one of them. */
    BLOCK,

    /** A KDoc comment: a block comment whose opening delimiter carries a second asterisk. */
    KDOC,
}

/** The kinds of literal text in Kotlin source text. */
internal enum class LiteralKind : SpanKind {
    /**
     * A piece of a string literal: its dollar prefix, its delimiters and its text up to and including the delimiters
     * of its template expressions. A string with template expressions is split into several pieces, because the
     * expressions between them are code.
     */
    STRING,

    /** A character literal with its quotes. */
    CHARACTER,

    /** An identifier in backticks, such as a test name, with its backticks. */
    BACKTICK_NAME,
}

/**
 * A stretch of Kotlin source text that is no code: a comment or literal text.
 *
 * @property kind what the stretch holds.
 * @property start the offset of its first character.
 * @property end the offset just past its last character.
 */
internal data class LexicalSpan(val kind: SpanKind, val start: Int, val end: Int)

/**
 * The one lexer of the architecture tests: it splits Kotlin source text, of `.kt` files and Gradle's `.kts` scripts
 * alike, into code and the spans that are no code. [KotlinComments] reads the comments from it, [SourceReferences] the
 * code that remains when the spans are blanked, and the budget tripwire ([WallClockAssertions]) the code that remains
 * when whole string literals are blanked as well.
 *
 * Only a lexer can tell code from text that merely looks like it, such as a URL in a string or a package name in a
 * test name, so this object follows the lexical rules of Kotlin as far as the architecture tests depend on them:
 *
 * - A line comment runs from two slashes to the end of the line, a block comment from a slash and an asterisk to the
 *   matching asterisk and slash. Block comments nest, KDoc included, so a nested opening delimiter needs its own
 *   closing one. A block comment whose opening delimiter has a second asterisk is KDoc, except the empty `/**/`.
 * - Nothing inside a string literal is a comment or code. A regular string ends at the next unescaped quote or,
 *   unterminated, at the end of the line; a backslash escapes the next character. A raw string has no escapes and
 *   ends at the last three of a run of three or more quotes, so a quote right before its closing delimiter belongs to
 *   the content.
 * - A string may open with a prefix of dollar signs, the multi-dollar interpolation of Kotlin 2. The length of the
 *   prefix, one for a string without one, is the number of dollar signs that open a template: a run of at least that
 *   many dollar signs directly before an opening brace starts a template expression, and the dollar signs before the
 *   last ones of a longer run are text. A shorter run is text, so in a string with a prefix of two dollar signs a
 *   single dollar sign before a brace opens nothing.
 * - A template expression, from its opening brace up to the brace that matches it, is code again inside either kind
 *   of string: it may hold comments, nested strings and braces of lambdas, which the lexer follows to any depth. A
 *   simple template such as `$name` holds an identifier only and stays part of the string.
 * - Character literals and identifiers in backticks are literal text, so `'"'` opens no string and a test name such
 *   as `` `a // b` `` holds no comment.
 *
 * An unterminated comment, string, character literal or backtick identifier ends with the text instead of failing,
 * because the compiler already rejects such a file and the lexer only has to terminate.
 */
internal object KotlinLexer {

    /** The comments and pieces of literal text of [source], in the order of their offsets. */
    fun spans(source: String): List<LexicalSpan> = Scan(source).spans()

    /**
     * The code of [source]: every character of a comment or of literal text is replaced by a space, line breaks
     * excepted, so offsets and line numbers in the result are those in [source]. Template expressions stay code, and
     * since the delimiters of a string and of its templates are blanked together, every bracket left is code and the
     * brackets pair up as in [source].
     *
     * @param lexed the spans of [source], for a caller that already has them, so the text is not lexed twice.
     */
    fun code(source: String, lexed: List<LexicalSpan> = spans(source)): String = blanked(source, lexed)

    /**
     * The code of [source] outside its string literals: like [code], but every string literal is blanked as a whole,
     * its template expressions included. It serves a reader to whom text built from values is text, such as the budget
     * tripwire, for which a duration written into an assertion message is no measurement.
     */
    fun codeOutsideStrings(source: String): String {
        val scan = Scan(source)
        val spans = scan.spans()
        return blanked(source, spans + scan.stringLiterals)
    }

    /** [source] with every character of [spans] replaced by a space, line breaks excepted. */
    private fun blanked(source: String, spans: List<LexicalSpan>): String {
        val code = StringBuilder(source)
        for (span in spans) {
            for (offset in span.start until span.end) {
                if (code[offset] != '\n') code.setCharAt(offset, ' ')
            }
        }
        return code.toString()
    }

    /**
     * One pass over [source]. Code and string bodies are scanned by two mutually recursive functions, so the nesting
     * of string templates inside strings inside template expressions is kept on the call stack.
     */
    private class Scan(private val source: String) {

        /** The spans found so far, in the order of their offsets. */
        private val found = mutableListOf<LexicalSpan>()

        /** Every string literal found so far as one span, from its dollar prefix or opening quote to its closing quote. */
        val stringLiterals = mutableListOf<LexicalSpan>()

        /** The offset of the next character to read. */
        private var position = 0

        /** Scans all of [source] as code and returns the spans it found; [stringLiterals] then holds its string literals. */
        fun spans(): List<LexicalSpan> {
            scanCode(insideTemplate = false)
            return found
        }

        /**
         * Scans code from [position]. Inside a template expression ([insideTemplate]) the scan stops at the closing
         * brace that matches the template's opening one, which belongs to the string around it; braces in between,
         * such as those of a lambda, are counted so they do not end it early. At the top level it runs to the end of
         * the text.
         */
        private fun scanCode(insideTemplate: Boolean) {
            var openBraces = 0
            while (position < source.length) {
                val char = source[position]
                when {
                    source.startsWith("//", position) -> skip(CommentKind.LINE, endOfLine(position))
                    source.startsWith("/*", position) -> skip(blockKind(position), endOfBlockComment(position))
                    char == '"' || char == '$' -> scanStringOrDollars()
                    char == '\'' -> skip(LiteralKind.CHARACTER, endOfQuoted(position))
                    char == '`' -> skip(LiteralKind.BACKTICK_NAME, endOfQuoted(position))
                    char == '{' -> {
                        openBraces++
                        position++
                    }
                    char == '}' && insideTemplate && openBraces == 0 -> return
                    char == '}' -> {
                        openBraces--
                        position++
                    }
                    else -> position++
                }
            }
        }

        /**
         * Scans the string literal that opens at [position], dollar prefix included, or steps over a run of dollar
         * signs that opens no string.
         */
        private fun scanStringOrDollars() {
            val start = position
            val quote = start + dollarRunLength(start)
            val templateDollars = maxOf(quote - start, 1)
            when {
                source.startsWith(RAW_QUOTES, quote) -> scanStringBody(start, quote + RAW_QUOTES.length, templateDollars, raw = true)
                source.startsWith("\"", quote) -> scanStringBody(start, quote + 1, templateDollars, raw = false)
                else -> position = quote
            }
        }

        /**
         * Scans the body of the string literal that starts at [start] and whose body starts at [bodyStart], and records
         * the string's pieces. A run of at least [templateDollars] dollar signs before an opening brace starts a
         * template expression, which is scanned as code. A [raw] string has no escapes and may span lines; a regular
         * string stops, unterminated, before a line break.
         */
        private fun scanStringBody(start: Int, bodyStart: Int, templateDollars: Int, raw: Boolean) {
            var pieceStart = start
            position = bodyStart
            while (position < source.length) {
                val char = source[position]
                when {
                    raw && source.startsWith(RAW_QUOTES, position) -> {
                        position = endOfQuoteRun(position)
                        break
                    }
                    !raw && char == '"' -> {
                        position++
                        break
                    }
                    !raw && char == '\n' -> break
                    !raw && char == '\\' -> position += 2
                    char == '$' -> pieceStart = scanDollars(pieceStart, templateDollars)
                    else -> position++
                }
            }
            position = minOf(position, source.length)
            record(LiteralKind.STRING, pieceStart, position)
            stringLiterals += LexicalSpan(LiteralKind.STRING, start, position)
        }

        /**
         * Steps over the run of dollar signs at [position] inside the body of a string whose current piece starts at
         * [pieceStart]. If the run opens a template expression, that is, it has at least [templateDollars] dollar signs
         * and an opening brace follows, the piece is recorded up to and including the brace, the expression is scanned
         * as code, and the offset of its closing brace is returned, where the next piece starts. Otherwise the run is
         * text and [pieceStart] is returned unchanged.
         */
        private fun scanDollars(pieceStart: Int, templateDollars: Int): Int {
            val run = dollarRunLength(position)
            position += run
            if (run < templateDollars || !source.startsWith("{", position)) return pieceStart
            position++
            record(LiteralKind.STRING, pieceStart, position)
            scanCode(insideTemplate = true)
            val closingBrace = position
            position = minOf(position + 1, source.length)
            return closingBrace
        }

        /** Records the span of [kind] that runs from [position] to [end] and continues after it. */
        private fun skip(kind: SpanKind, end: Int) {
            record(kind, position, end)
            position = end
        }

        /** Records the span of [kind] from [start] to [end], unless it is empty. */
        private fun record(kind: SpanKind, start: Int, end: Int) {
            if (end > start) found += LexicalSpan(kind, start, end)
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

        /** The number of dollar signs in the run that starts at [start]. */
        private fun dollarRunLength(start: Int): Int {
            var offset = start
            while (offset < source.length && source[offset] == '$') offset++
            return offset - start
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
    }

    /** The delimiter of a raw string, three quotes. */
    private const val RAW_QUOTES = "\"\"\""
}
