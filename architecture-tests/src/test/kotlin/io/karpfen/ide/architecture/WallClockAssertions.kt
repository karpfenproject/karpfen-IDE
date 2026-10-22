package io.karpfen.ide.architecture

/**
 * A function declared in a test source file.
 *
 * @property name the name by which other functions of the file call it.
 * @property text the whole declaration as written, signature and body included.
 */
internal data class FunctionSource(val name: String, val text: String)

/**
 * Recognizes the functions of one test source file that assert on a wall-clock measurement: the functions that the
 * budget tripwire requires to be tagged `budget` when they are tests.
 *
 * A function asserts on a wall-clock measurement if
 *
 * - it calls an assertion, a function whose name starts with `assert`, `check` or `require` (the assertions of
 *   kotlin.test and JUnit, the preconditions of the standard library and helpers named like them), with an argument
 *   or a trailing lambda that mentions a measured value ([MeasuredValues]), whatever the limit is compared with: a
 *   named budget, a literal duration or another measurement;
 * - it calls one of JUnit's timeout assertions, whose names start with `assertTimeout`, which measure the wall clock
 *   themselves; or
 * - it calls another function of the file that asserts on a wall-clock measurement, such as a helper that measures a
 *   block and asserts on the result.
 *
 * Measurements flow between the functions of the file: a function whose result is a measurement, by a `return`
 * statement or its expression body, makes every call of it a measurement, as a helper that averages the duration of a
 * block does. Both sets of functions are grown until they no longer change.
 *
 * A wait for an event with a time limit, such as a loop that polls until a deadline, compares the wall clock but asserts
 * nothing on it, and a test that only reports a measured duration asserts nothing either, so neither is recognized: a
 * functional test with a generous time limit keeps running. Assertions in helpers of another file are not followed.
 *
 * @param functions all functions of the file, those nested in classes and declared locally included.
 */
internal class WallClockAssertions(private val functions: List<FunctionSource>) {

    /** The code of each function, comments and literal text blanked. */
    private val codes: Map<FunctionSource, String> = functions.associateWith { KotlinLexer.code(it.text) }

    /** The names of the functions whose result is a wall-clock measurement. */
    private val measuringFunctions: Set<String> = grow(::returnsMeasurement).map { it.name }.toSet()

    /** The functions that call an assertion on a measured value themselves. */
    private val assertingThemselves: Set<FunctionSource> = functions.filter(::assertsOnMeasurement).toSet()

    /** The functions that assert on a wall-clock measurement, themselves or through the functions they call. */
    private val asserting: Set<FunctionSource> =
        grow { function, known -> function in assertingThemselves || callsAny(function, known) }

    /** True if [function], one of the functions of the file, asserts on a wall-clock measurement. */
    fun assertsOnWallClock(function: FunctionSource): Boolean = function in asserting

    /** True if [function] returns a measurement, given the names of the functions [known] to return one. */
    private fun returnsMeasurement(function: FunctionSource, known: Set<FunctionSource>): Boolean {
        val code = codes.getValue(function)
        val values = MeasuredValues(code, known.map { it.name })
        val results = RETURN.findAll(code).map { CodeRegions.statementFrom(code, it.range.last + 1) } +
            listOfNotNull(CodeRegions.expressionBody(code))
        return results.any { values.areMentionedIn(it) }
    }

    /** True if [function] calls an assertion on a measured value or a timeout assertion. */
    private fun assertsOnMeasurement(function: FunctionSource): Boolean {
        val code = codes.getValue(function)
        val calls = ASSERTION.findAll(code).filterNot { CodeRegions.isDeclaredAt(code, it.range.first) }.toList()
        if (calls.isEmpty()) return false
        val values = MeasuredValues(code, measuringFunctions)
        return calls.any { call ->
            call.value.startsWith(TIMEOUT_ASSERTION) || values.areMentionedIn(CodeRegions.callArguments(code, call.range.last + 1))
        }
    }

    /** True if [function] calls one of the functions [known] to assert on a measurement, other than itself. */
    private fun callsAny(function: FunctionSource, known: Set<FunctionSource>): Boolean =
        FunctionCalls(known.filter { it != function }.map { it.name }).areCalledIn(codes.getValue(function))

    /**
     * The functions that satisfy [condition], given those found so far, grown from none until the set no longer
     * changes. [condition] only ever holds for more functions when it knows more, so the set grows and the loop ends.
     */
    private fun grow(condition: (FunctionSource, Set<FunctionSource>) -> Boolean): Set<FunctionSource> {
        var found = emptySet<FunctionSource>()
        while (true) {
            val next = functions.filter { condition(it, found) }.toSet()
            if (next == found) return found
            found = next
        }
    }

    private companion object {
        /** The keyword `return`, with or without a label. */
        val RETURN = Regex("""\breturn\b(?:@\w+)?""")

        /** The name of an assertion, followed by its argument list or a trailing lambda. */
        val ASSERTION = Regex("""\b(?:assert|check|require)\w*(?=[ \t]*[({])""")

        /** The start of the names of JUnit's timeout assertions. */
        const val TIMEOUT_ASSERTION = "assertTimeout"
    }
}
