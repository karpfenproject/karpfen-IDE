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
 * Finds regions in Kotlin code whose comments and literal text are blanked, as [KotlinLexer.code] and
 * [KotlinLexer.codeOutsideStrings] return it. The regions are statements, the arguments of a call, the expression body
 * of a function and the operands around an operator. In such code every bracket is code, so the brackets pair up and a
 * region can be delimited by counting them. Generic angle brackets are not counted. Without a parser they cannot be
 * told from comparisons, and no region depends on them. A region is a range of offsets in the code, so a reader can
 * tell where in the function a name is mentioned.
 */
internal object CodeRegions {

    /** The brackets that open a nested region. */
    private const val OPENING = "([{"

    /** The brackets that close a nested region. */
    private const val CLOSING = ")]}"

    /** The last character of a line after which a statement goes on: a binary operator, an assignment, a comma or a dot. */
    private const val CONTINUING_LINE_ENDS = "=+-*/%&|,."

    /** The start of a line that continues the statement of the line before: a member access, an elvis or an operator. */
    private val CONTINUING_LINE_START = Regex("""\?\.|\?:|\.|&&|\|\||[+\-*/%]|[<>!=]=""")

    /** The keyword `fun`, optional type parameters and an optional receiver type, right before a declared name. */
    private val DECLARATION_PREFIX = Regex("""\bfun\s+(?:<[^>\n]*>\s*)?(?:[\w<>?,\s]+\.)?$""")

    /** The keyword `fun`. */
    private val FUN_KEYWORD = Regex("""\bfun\b""")

    /** The offset just past the bracket that closes the one at [open] in [code], or the end of [code] if none does. */
    fun endOfBracket(code: String, open: Int): Int {
        var depth = 0
        for (offset in open until code.length) {
            when (code[offset]) {
                in OPENING -> depth++
                in CLOSING -> {
                    depth--
                    if (depth == 0) return offset + 1
                }
            }
        }
        return code.length
    }

    /** The offset of the bracket that opens the one at [close] in [code], or 0 if none does. */
    fun startOfBracket(code: String, close: Int): Int {
        var depth = 0
        for (offset in close downTo 0) {
            when (code[offset]) {
                in CLOSING -> depth++
                in OPENING -> {
                    depth--
                    if (depth == 0) return offset
                }
            }
        }
        return 0
    }

    /** The innermost bracket that is open at [offset] in [code], or null if none is. */
    fun enclosingBracket(code: String, offset: Int): Char? {
        var depth = 0
        for (index in offset - 1 downTo 0) {
            when (code[index]) {
                in CLOSING -> depth++
                in OPENING -> if (depth == 0) return code[index] else depth--
            }
        }
        return null
    }

    /**
     * The statement of [code] that starts at [start]. It ends before a semicolon or a line break outside brackets, or
     * before the bracket that closes one opened before [start], such as the end of the lambda it is written in. A line
     * break does not end it when the statement goes on in the next line: the line ends with a character of
     * [CONTINUING_LINE_ENDS], or the next line starts with [CONTINUING_LINE_START].
     */
    fun statementFrom(code: String, start: Int): IntRange {
        var depth = 0
        for (offset in start until code.length) {
            val char = code[offset]
            when {
                char in OPENING -> depth++
                char in CLOSING && depth == 0 -> return start until offset
                char in CLOSING -> depth--
                depth == 0 && char == ';' -> return start until offset
                depth == 0 && char == '\n' && !continuesAfter(code, start, offset) -> return start until offset
            }
        }
        return start until code.length
    }

    /**
     * The arguments of the call in [code] whose name ends at [nameEnd]: its argument list in parentheses and its
     * trailing lambda, both optional, or an empty range if neither follows the name.
     */
    fun callArguments(code: String, nameEnd: Int): IntRange {
        val list = argumentList(code, nameEnd)
        val afterList = skipBlanks(code, list.last + 1)
        val end = if (afterList < code.length && code[afterList] == '{') endOfBracket(code, afterList) else list.last + 1
        return list.first until end
    }

    /**
     * The argument list in parentheses of the call in [code] whose name ends at [nameEnd], the parentheses included,
     * without a trailing lambda. It is an empty range if no parenthesis follows the name.
     */
    fun argumentList(code: String, nameEnd: Int): IntRange {
        val start = skipBlanks(code, nameEnd)
        return if (start < code.length && code[start] == '(') start until endOfBracket(code, start) else start until start
    }

    /** True if the name that starts at [start] in [code] is declared there, after the keyword `fun`, not called. */
    fun isDeclaredAt(code: String, start: Int): Boolean {
        val lineStart = code.lastIndexOf('\n', start - 1) + 1
        return DECLARATION_PREFIX.containsMatchIn(code.substring(lineStart, start))
    }

    /**
     * The expression body of the first function declared in [code]: everything after the equals sign that follows its
     * parameter list and return type. Null if the function has a block body or none.
     */
    fun expressionBody(code: String): IntRange? {
        val keyword = FUN_KEYWORD.find(code) ?: return null
        val parameters = code.indexOf('(', keyword.range.last + 1).takeIf { it >= 0 } ?: return null
        var depth = 0
        for (offset in endOfBracket(code, parameters) until code.length) {
            when (code[offset]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '{' -> if (depth == 0) return null
                '=' -> if (depth == 0) return offset + 1 until code.length
            }
        }
        return null
    }

    /**
     * The start of the operand that ends just before [end] in [code], blanks and line breaks before [end] skipped. An
     * operand is a chain of names and number literals joined by dots, safe calls or non-null assertions, with the
     * argument lists, index brackets and trailing lambdas of its calls, or an expression in parentheses: `items` before
     * `.forEach`, `measureTime { work() }` before `.let`, `System.nanoTime()` before a minus. Returns [end] itself if no
     * operand ends there, as before a unary minus.
     */
    fun startOfOperandBefore(code: String, end: Int): Int {
        var position = skipWhitespaceBackward(code, end)
        var start = end
        while (position > 0) {
            val char = code[position - 1]
            when {
                char in CLOSING -> {
                    position = startOfBracket(code, position - 1)
                    start = position
                    if (char == '}') position = skipBlanksBackward(code, position)
                }
                char.isNamePart() -> {
                    while (position > 0 && code[position - 1].isNamePart()) position--
                    start = position
                    position = skipMemberAccessBackward(code, position) ?: return start
                }
                code.startsWith("!!", position - 2) -> position -= 2
                else -> return start
            }
            if (position == 0 || !(code[position - 1].isNamePart() || code[position - 1] in CLOSING)) return start
        }
        return start
    }

    /**
     * The end of the operand that starts at [start] in [code], blanks and line breaks after [start] skipped, read as in
     * [startOfOperandBefore]: a name or number literal, or an expression in parentheses, with the member accesses,
     * calls, index brackets and trailing lambdas that follow. Returns [start] itself if no operand starts there.
     */
    fun endOfOperandAfter(code: String, start: Int): Int {
        var position = skipWhitespace(code, start)
        position = when {
            position < code.length && code[position].isNamePart() -> endOfName(code, position)
            position < code.length && code[position] == '(' -> endOfBracket(code, position)
            else -> return start
        }
        while (position < code.length) {
            val next = skipBlanks(code, position)
            position = when {
                code[position] == '(' || code[position] == '[' -> endOfBracket(code, position)
                next < code.length && code[next] == '{' -> endOfBracket(code, next)
                code.startsWith("!!", position) -> position + 2
                code.startsWith("?.", position) -> endOfName(code, position + 2)
                code[position] == '.' -> endOfName(code, position + 1)
                else -> return position
            }
        }
        return position
    }

    /**
     * True if the statement that started at [statementStart] goes on after the line break at [lineBreak]. That is the
     * case when nothing of the statement has been written yet, as when it starts after an equals sign at the end of a
     * line. It is also the case when the text of the statement so far ends with an operator, or when the next line
     * that is not blank starts with one.
     */
    private fun continuesAfter(code: String, statementStart: Int, lineBreak: Int): Boolean {
        val lineEnd = code.substring(statementStart, lineBreak).trimEnd().lastOrNull() ?: return true
        if (lineEnd in CONTINUING_LINE_ENDS) return true
        var next = lineBreak
        while (next < code.length && code[next].isWhitespace()) next++
        return CONTINUING_LINE_START.matchesAt(code, next)
    }

    /**
     * The offset before the member access that ends at [nameStart] in [code], with the blanks and line breaks before
     * it. The member access is a dot, a safe call, or a non-null assertion with its dot. Null if no member access ends
     * there.
     */
    private fun skipMemberAccessBackward(code: String, nameStart: Int): Int? {
        if (nameStart == 0 || code[nameStart - 1] != '.') return null
        var position = nameStart - 1
        if (position > 0 && code[position - 1] == '?') position--
        if (position > 1 && code[position - 1] == '!' && code[position - 2] == '!') position -= 2
        return skipWhitespaceBackward(code, position)
    }

    /** True for a character of a name or a number literal. */
    private fun Char.isNamePart(): Boolean = isLetterOrDigit() || this == '_'

    /** The offset just past the name or number literal that starts at [start] in [code]. */
    private fun endOfName(code: String, start: Int): Int {
        var offset = start
        while (offset < code.length && code[offset].isNamePart()) offset++
        return offset
    }

    /** The offset of the first character at or after [from] in [code] that is neither a blank nor a tab. */
    private fun skipBlanks(code: String, from: Int): Int {
        var offset = from
        while (offset < code.length && (code[offset] == ' ' || code[offset] == '\t')) offset++
        return offset
    }

    /** The offset of the first character at or after [from] in [code] that is no whitespace. */
    private fun skipWhitespace(code: String, from: Int): Int {
        var offset = from
        while (offset < code.length && code[offset].isWhitespace()) offset++
        return offset
    }

    /** The offset just past the last character before [end] in [code] that is neither a blank nor a tab. */
    private fun skipBlanksBackward(code: String, end: Int): Int {
        var offset = end
        while (offset > 0 && (code[offset - 1] == ' ' || code[offset - 1] == '\t')) offset--
        return offset
    }

    /** The offset just past the last character before [end] in [code] that is no whitespace. */
    private fun skipWhitespaceBackward(code: String, end: Int): Int {
        var offset = end
        while (offset > 0 && code[offset - 1].isWhitespace()) offset--
        return offset
    }
}

/** The text of [range] in this code, for a region of [CodeRegions]. */
internal fun String.textOf(range: IntRange): String = substring(range.first, range.last + 1)

/**
 * Recognizes calls of the functions named [names] in Kotlin code from which comments and literal text are blanked: a
 * name followed by an argument list or a trailing lambda. The declaration of a function with such a name is no call.
 */
internal class FunctionCalls(names: Collection<String>) {

    /**
     * One of the names, followed by an argument list or a trailing lambda. Null if there are no names. A name that is
     * no plain identifier, such as a test name in backticks, is left out, because the lexer blanks it in code.
     */
    private val call: Regex? = names.filter { IDENTIFIER.matches(it) }.distinct().takeIf { it.isNotEmpty() }
        ?.let { identifiers -> Regex("""\b(?:${identifiers.joinToString("|")})(?=[ \t]*[({])""") }

    /** True if [region] of [code] calls one of the functions. */
    fun areCalledIn(code: String, region: IntRange = code.indices): Boolean =
        call?.matchesIn(code, region)?.any { !CodeRegions.isDeclaredAt(code, it.range.first) } ?: false

    private companion object {
        /** A plain Kotlin identifier. */
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    }
}

/** The matches of this pattern in [code] that lie wholly inside [region]. Lookarounds still see the text around it. */
internal fun Regex.matchesIn(code: String, region: IntRange): Sequence<MatchResult> =
    if (region.isEmpty()) {
        emptySequence()
    } else {
        findAll(code, region.first).takeWhile { it.range.first <= region.last }.filter { it.range.last <= region.last }
    }
