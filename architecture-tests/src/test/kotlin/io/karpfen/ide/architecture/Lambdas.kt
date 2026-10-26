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
 * A lambda in Kotlin code.
 *
 * @property body the code between its braces.
 * @property parameters the names of its parameters: those before its arrow, or `it` for a lambda without an arrow; empty
 *   for a lambda whose parameters carry types, whose names are not read.
 * @property inputs the code that gives the parameters their values: the receiver and the argument list of the call that
 *   takes the lambda as its trailing lambda, as far as the call has them; empty for a lambda passed in any other way.
 */
internal class Lambda(val body: IntRange, val parameters: List<String>, val inputs: List<IntRange>)

/**
 * Finds the lambdas in Kotlin code from which comments and literal text are blanked. An opening brace opens a lambda or
 * a block of another kind, and the code before it tells which:
 *
 * - A block follows a keyword of control flow or of a declaration body (`if (…)`, `else`, `when`, `try`, `catch (…)`,
 *   `finally`, `do`, `for (…)`, `while (…)`, `init`, `get()`, `set(…)`, `constructor(…)`, `companion object`, an
 *   anonymous `fun (…)`), an arrow, as in a branch of `when`, a closing angle bracket of a type, a declared function with
 *   its parameter list, a type after a colon, or the name of a class, interface or object with its optional parameter
 *   list.
 * - A trailing lambda follows the name of a call or its argument list. The receiver of the call and its argument list
 *   are the lambda's inputs, so in `items.forEach { item -> … }` the parameter `item` takes its value from `items`, and
 *   in `measureTime { work() }.let { … }` the parameter `it` takes the measurement.
 * - Any other brace opens a lambda without inputs, such as a lambda literal after an equals sign or in an argument list.
 *
 * The rule is lexical. It does not know the signature of the call, so the lambda of a function that passes no
 * parameter, such as `run { … }`, still counts as binding `it`.
 */
internal object Lambdas {

    /** The keywords after which a brace opens a block that is no lambda. */
    private val BLOCK_KEYWORDS = setOf(
        "if", "else", "when", "try", "catch", "finally", "do", "for", "while", "init", "get", "set", "constructor", "object",
        "fun",
    )

    /** The keywords before the name of a declaration whose body is a block. */
    private val DECLARATION_KEYWORDS = setOf("class", "interface", "object")

    /** The parameters of a lambda without types, plain or destructured, before its arrow on the line of its opening brace. */
    private val PARAMETERS = Regex("""\{[ \t]*([\w \t,()]+?)[ \t]*->""")

    /** The parameters of a lambda with types before its arrow on the line of its opening brace. */
    private val TYPED_PARAMETERS = Regex("""\{[ \t]*[\w \t,()]*:[\w \t,()<>?.:]*?->""")

    /** A plain identifier; the underscore that skips a destructured value is left out. */
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

    /** The lambdas of [code], in the order of their opening braces. */
    fun findIn(code: String): List<Lambda> =
        code.indices.filter { code[it] == '{' }.mapNotNull { brace ->
            inputsOf(code, brace)?.let { inputs ->
                Lambda(brace + 1 until CodeRegions.endOfBracket(code, brace) - 1, parametersOf(code, brace), inputs)
            }
        }

    /**
     * The inputs of the lambda that opens with the brace at [brace] in [code]; null if the brace opens a block that is
     * no lambda.
     */
    private fun inputsOf(code: String, brace: Int): List<IntRange>? {
        val before = lastCodeBefore(code, brace)
        val previous = code.getOrNull(before - 1) ?: return emptyList()
        return when {
            previous == ')' -> {
                val arguments = CodeRegions.startOfBracket(code, before - 1) until before
                val name = nameBefore(code, arguments.first) ?: return emptyList()
                if (opensBlock(code, name)) null else listOfNotNull(receiverOf(code, name.first), arguments)
            }
            previous.isLetterOrDigit() || previous == '_' -> {
                val name = nameBefore(code, before) ?: return emptyList()
                if (opensBlock(code, name)) null else listOfNotNull(receiverOf(code, name.first))
            }
            previous == '>' -> null
            else -> emptyList()
        }
    }

    /**
     * True if the name at [name] in [code], followed by the brace or by an argument list and the brace, makes the brace
     * open a block: a keyword of [BLOCK_KEYWORDS], a declared function, a type after a colon, or a declared class,
     * interface or object.
     */
    private fun opensBlock(code: String, name: IntRange): Boolean {
        val chainStart = CodeRegions.startOfOperandBefore(code, name.last + 1)
        val wordBefore = nameBefore(code, chainStart)?.let(code::textOf)
        return code.textOf(name) in BLOCK_KEYWORDS ||
            CodeRegions.isDeclaredAt(code, name.first) ||
            code.getOrNull(lastCodeBefore(code, chainStart) - 1) == ':' ||
            wordBefore in DECLARATION_KEYWORDS
    }

    /**
     * The receiver of the call whose name starts at [nameStart] in [code], before its dot, safe call or non-null
     * assertion with a dot; null if the name follows no dot.
     */
    private fun receiverOf(code: String, nameStart: Int): IntRange? {
        val dot = lastCodeBefore(code, nameStart) - 1
        if (dot < 0 || code[dot] != '.') return null
        val access = when {
            code.startsWith("?.", dot - 1) -> dot - 1
            code.startsWith("!!.", dot - 2) -> dot - 2
            else -> dot
        }
        return CodeRegions.startOfOperandBefore(code, access) until access
    }

    /** The name that ends just before [end] in [code], blanks skipped; null if no name ends there. */
    private fun nameBefore(code: String, end: Int): IntRange? {
        val nameEnd = lastCodeBefore(code, end)
        var nameStart = nameEnd
        while (nameStart > 0 && (code[nameStart - 1].isLetterOrDigit() || code[nameStart - 1] == '_')) nameStart--
        return if (nameStart < nameEnd) nameStart until nameEnd else null
    }

    /** The offset just past the last character before [end] in [code] that is no whitespace. */
    private fun lastCodeBefore(code: String, end: Int): Int {
        var offset = end
        while (offset > 0 && code[offset - 1].isWhitespace()) offset--
        return offset
    }

    /** The parameters of the lambda that opens with the brace at [brace] in [code], as [Lambda.parameters] defines them. */
    private fun parametersOf(code: String, brace: Int): List<String> {
        PARAMETERS.matchAt(code, brace)?.let { match ->
            return IDENTIFIER.findAll(match.groupValues[1]).map { it.value }.filter { it != "_" }.toList()
        }
        return if (TYPED_PARAMETERS.matchAt(code, brace) != null) emptyList() else listOf("it")
    }
}
