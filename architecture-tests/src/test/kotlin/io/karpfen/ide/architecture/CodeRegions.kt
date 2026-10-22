package io.karpfen.ide.architecture

/**
 * Finds regions in Kotlin code from which comments and literal text are blanked ([KotlinLexer.code]): statements, the
 * arguments of a call, the expression body of a function. In such code every bracket is code, so brackets pair up and
 * a region can be delimited by counting them. Generic angle brackets are not counted, because they cannot be told
 * from comparisons without a parser and no region depends on them.
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

    /**
     * The statement of [code] that starts at [start]. It ends before a semicolon or a line break outside brackets, or
     * before the bracket that closes one opened before [start], such as the end of the lambda it is written in. A line
     * break does not end it when the statement goes on in the next line: the line ends with a character of
     * [CONTINUING_LINE_ENDS], or the next line starts with [CONTINUING_LINE_START].
     */
    fun statementFrom(code: String, start: Int): String {
        var depth = 0
        for (offset in start until code.length) {
            val char = code[offset]
            when {
                char in OPENING -> depth++
                char in CLOSING && depth == 0 -> return code.substring(start, offset)
                char in CLOSING -> depth--
                depth == 0 && char == ';' -> return code.substring(start, offset)
                depth == 0 && char == '\n' && !continuesAfter(code, start, offset) -> return code.substring(start, offset)
            }
        }
        return code.substring(start)
    }

    /**
     * The arguments of the call in [code] whose name ends at [nameEnd]: its argument list in parentheses and its
     * trailing lambda, both optional, or an empty string if neither follows the name.
     */
    fun callArguments(code: String, nameEnd: Int): String {
        val start = skipBlanks(code, nameEnd)
        var end = start
        if (end < code.length && code[end] == '(') end = skipBlanks(code, endOfBracket(code, end))
        if (end < code.length && code[end] == '{') end = endOfBracket(code, end)
        return code.substring(start, end)
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
    fun expressionBody(code: String): String? {
        val keyword = FUN_KEYWORD.find(code) ?: return null
        val parameters = code.indexOf('(', keyword.range.last + 1).takeIf { it >= 0 } ?: return null
        var depth = 0
        for (offset in endOfBracket(code, parameters) until code.length) {
            when (code[offset]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '{' -> if (depth == 0) return null
                '=' -> if (depth == 0) return code.substring(offset + 1)
            }
        }
        return null
    }

    /**
     * True if the statement that started at [statementStart] goes on after the line break at [lineBreak]: nothing of it
     * has been written yet, as after an equals sign at the end of a line, its text so far ends with an operator, or
     * the next line that is not blank starts with one.
     */
    private fun continuesAfter(code: String, statementStart: Int, lineBreak: Int): Boolean {
        val lineEnd = code.substring(statementStart, lineBreak).trimEnd().lastOrNull() ?: return true
        if (lineEnd in CONTINUING_LINE_ENDS) return true
        var next = lineBreak
        while (next < code.length && code[next].isWhitespace()) next++
        return CONTINUING_LINE_START.matchesAt(code, next)
    }

    /** The offset of the first character at or after [from] in [code] that is neither a blank nor a tab. */
    private fun skipBlanks(code: String, from: Int): Int {
        var offset = from
        while (offset < code.length && (code[offset] == ' ' || code[offset] == '\t')) offset++
        return offset
    }
}

/**
 * Recognizes calls of the functions named [names] in Kotlin code from which comments and literal text are blanked: a
 * name followed by an argument list or a trailing lambda. The declaration of a function with such a name is no call.
 */
internal class FunctionCalls(names: Collection<String>) {

    /**
     * One of the names, followed by an argument list or a trailing lambda; null if there are none. A name that is no
     * plain identifier, such as a test name in backticks, is left out, because the lexer blanks it in code.
     */
    private val call: Regex? = names.filter { IDENTIFIER.matches(it) }.distinct().takeIf { it.isNotEmpty() }
        ?.let { identifiers -> Regex("""\b(?:${identifiers.joinToString("|")})(?=[ \t]*[({])""") }

    /** True if [code] calls one of the functions. */
    fun areCalledIn(code: String): Boolean =
        call?.findAll(code)?.any { !CodeRegions.isDeclaredAt(code, it.range.first) } ?: false

    private companion object {
        /** A plain Kotlin identifier. */
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    }
}
