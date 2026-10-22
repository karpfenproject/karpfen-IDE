package io.karpfen.ide.architecture

/**
 * The values in the code of one function that hold a wall-clock measurement, found by following each measurement from
 * where the wall clock is read through the names it is bound to.
 *
 * A measurement is a read of the wall clock ([WALL_CLOCK_READ]), a call of one of the [measuringFunctions], or a name
 * bound to a measurement. A name is bound to one when the code that gives the name its value mentions a measurement:
 *
 * - a `val` or `var`, destructuring declarations included, by its initializer up to the end of the statement, so
 *   `val average = mark.elapsedNow() / runs` binds `average` once `mark` is bound;
 * - the parameters of a lambda by the code before the lambda on the same line, so
 *   `measurements.forEach { (name, duration) -> … }` binds `name` and `duration` once `measurements` is bound;
 * - the variables of a `for` loop by the expression after `in`.
 *
 * Names are followed until no more are bound. The rule reads [code] as text, so it neither knows types nor scopes: a
 * name bound in one lambda counts in all of the function, and a value passed on through a function parameter, a
 * property or a type-annotated lambda parameter is lost. Virtual time is no measurement, so a mark of a
 * `TestTimeSource` or a `measureTime` called on one binds nothing.
 *
 * @property code the code of the function, with comments and literal text blanked ([KotlinLexer.code]).
 * @param measuringFunctions the names of the functions of the same file that return a measurement.
 */
internal class MeasuredValues(private val code: String, measuringFunctions: Collection<String>) {

    /** The calls of the functions that return a measurement. */
    private val measuringCalls = FunctionCalls(measuringFunctions)

    /** The names in [code] that are bound to a measurement. */
    val names: Set<String> = boundNames()

    /** A mention of one of [names] that is not the member of another value; null if there are none. */
    private val nameMention: Regex? = mentionOf(names)

    /** True if [region], a part of the function's code, reads the wall clock or uses a measured value. */
    fun areMentionedIn(region: String): Boolean = mentions(region, nameMention)

    /** The names bound to a measurement, grown from the bindings that mention one until no more are bound. */
    private fun boundNames(): Set<String> {
        val bindings = bindings()
        var bound = emptySet<String>()
        while (true) {
            val mention = mentionOf(bound)
            val next = bound + bindings.filter { mentions(it.source, mention) }.flatMap { it.names }
            if (next == bound) return bound
            bound = next
        }
    }

    /** True if [region] reads the wall clock, calls a measuring function or matches [nameMention]. */
    private fun mentions(region: String, nameMention: Regex?): Boolean =
        WALL_CLOCK_READ.containsMatchIn(region) ||
            measuringCalls.areCalledIn(region) ||
            nameMention?.containsMatchIn(region) == true

    /** Every place in [code] that binds names, with the code that gives them their value. */
    private fun bindings(): List<Binding> {
        val declarations = DECLARATION.findAll(code).map { match ->
            Binding(identifiersIn(match.groupValues[1].ifEmpty { match.groupValues[2] }), CodeRegions.statementFrom(code, match.range.last + 1))
        }
        val lambdaParameters = LAMBDA_PARAMETERS.findAll(code).map { match ->
            val lineStart = code.lastIndexOf('\n', match.range.first) + 1
            Binding(identifiersIn(match.groupValues[1]), code.substring(lineStart, match.range.first))
        }
        val loopVariables = FOR_LOOP.findAll(code).map { match ->
            val header = code.substring(match.range.last + 1, CodeRegions.endOfBracket(code, match.range.first + match.value.indexOf('(')))
            Binding(identifiersIn(match.groupValues[1]), header)
        }
        return (declarations + lambdaParameters + loopVariables).toList()
    }

    /**
     * Names and the code that gives them their value.
     *
     * @property names the names bound.
     * @property source the code whose value they hold.
     */
    private class Binding(val names: List<String>, val source: String)

    private companion object {
        /**
         * A read of the wall clock: the monotonic time source, the top-level measuring functions of the Kotlin
         * standard library (not when called as a member, such as on a test time source), the system clock or a
         * date-time clock.
         */
        val WALL_CLOCK_READ = Regex(
            """\bTimeSource\.Monotonic\b|(?<![\w.])(?:measureTimedValue|measureTime|measureNanoTime|measureTimeMillis)\b|""" +
                """\bSystem\.(?:nanoTime|currentTimeMillis)\b|\bClock\.System\b|\bInstant\.now\b""",
        )

        /**
         * A `val` or `var` with its name, or its destructured names in parentheses, an optional type and the equals
         * sign of its initializer. Group one holds destructured names, group two a single name.
         */
        val DECLARATION = Regex("""\b(?:val|var)\s+(?:\(([^()]*)\)|(\w+))(?:\s*:[^=\n]*)?\s*=(?!=)""")

        /** The parameters of a lambda, plain or destructured, before its arrow on the line of its opening brace. */
        val LAMBDA_PARAMETERS = Regex("""\{[ \t]*([\w \t,()]+?)[ \t]*->""")

        /** The head of a `for` loop up to `in`; group one holds its variables, plain or destructured. */
        val FOR_LOOP = Regex("""\bfor\s*\(\s*([\w\s,()]+?)\s+in\b""")

        /** A plain identifier; the underscore that skips a destructured value is left out by [identifiersIn]. */
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

        /** The identifiers in [text], a list of names, without the placeholder underscore. */
        fun identifiersIn(text: String): List<String> = IDENTIFIER.findAll(text).map { it.value }.filter { it != "_" }.toList()

        /** A mention of one of [names] that is not the member of another value, or null if there are no names. */
        fun mentionOf(names: Set<String>): Regex? =
            if (names.isEmpty()) null else Regex("""(?<![\w.])(?:${names.joinToString("|")})\b""")
    }
}
