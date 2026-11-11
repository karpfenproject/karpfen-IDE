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
 * A function asserts on a wall-clock measurement in four cases:
 *
 * - It calls an assertion with an argument that holds a measurement ([MeasuredValues]). An assertion is a function
 *   whose name starts with `assert`, `check` or `require`. These are the assertions of kotlin.test and JUnit, the
 *   preconditions of the standard library, and helpers named like them. What the measurement is compared with does not
 *   matter. It may be a named budget, a literal duration or another measurement. The trailing lambda of an assertion
 *   counts as an argument, because it may be the condition, as in kotlin.test's `assertTrue { … }`. The trailing lambda
 *   of a precondition is its lazy message and does not count.
 * - It fails, by `fail(…)`, `error(…)` or `throw`, in the branch of an `if` whose condition holds a measurement.
 * - It calls one of JUnit's timeout assertions, or runs a coroutine test, with a tight literal limit ([TimeLimits]).
 * - It calls another function of the file that asserts on a wall-clock measurement, such as a helper that measures a
 *   block and asserts on the result.
 *
 * Measurements flow between the functions of the file. A function whose result is a measurement, by a `return`
 * statement or by its expression body, makes every call of it a measurement. A helper that averages the duration of a
 * block is an example. Both sets of functions are grown until they no longer change.
 *
 * The code is read without its string literals ([KotlinLexer.codeOutsideStrings]), so a duration written into an
 * assertion message or a report is no measurement. A timestamp is no measurement either. A test that compares a stored
 * instant asserts nothing on a duration. Neither does a test that waits for an event until a deadline, with a loop
 * that compares the clock with the deadline or checks `hasNotPassedNow()`. A test that only reports a measured
 * duration asserts nothing on it either. So a functional test with a generous time limit keeps running. Assertions in
 * helpers of another file are not followed.
 *
 * @param functions all functions of the file, those nested in classes and declared locally included.
 */
internal class WallClockAssertions(private val functions: List<FunctionSource>) {

    /** The code of each function, comments and string literals blanked. */
    private val codes: Map<FunctionSource, String> = functions.associateWith { KotlinLexer.codeOutsideStrings(it.text) }

    /** The names of the functions whose result is a wall-clock measurement. */
    private val measuringFunctions: Set<String> = grow(::returnsMeasurement).map { it.name }.toSet()

    /** The functions that assert on a measured value themselves. */
    private val assertingThemselves: Set<FunctionSource> = functions.filter(::assertsThemselves).toSet()

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
        return results.any(values::areMentionedIn)
    }

    /**
     * True if [function] asserts on a measured value itself, by an assertion call or a failing branch, or has a tight
     * timeout.
     */
    private fun assertsThemselves(function: FunctionSource): Boolean {
        val code = codes.getValue(function)
        if (TimeLimits.hasTightTimeoutIn(code)) return true
        val asserted = assertionArguments(code) + failingConditions(code)
        if (asserted.isEmpty()) return false
        val values = MeasuredValues(code, measuringFunctions)
        return asserted.any(values::areMentionedIn)
    }

    /**
     * The arguments of the assertion calls in [code]: the argument list and the trailing lambda of an assertion, which
     * may be its condition, and the argument list only of a precondition, whose trailing lambda is its lazy message.
     */
    private fun assertionArguments(code: String): List<IntRange> =
        ASSERTION.findAll(code)
            .filterNot { CodeRegions.isDeclaredAt(code, it.range.first) }
            .map { call ->
                val nameEnd = call.range.last + 1
                if (PRECONDITION.matches(call.value)) CodeRegions.argumentList(code, nameEnd) else CodeRegions.callArguments(code, nameEnd)
            }
            .toList()

    /** The conditions of the `if` expressions in [code] whose branch fails. */
    private fun failingConditions(code: String): List<IntRange> =
        IF.findAll(code)
            .map { CodeRegions.argumentList(code, it.range.last + 1) }
            .filter { condition -> !condition.isEmpty() && fails(code, CodeRegions.statementFrom(code, condition.last + 1)) }
            .toList()

    /** True if [branch], a region of [code], fails. */
    private fun fails(code: String, branch: IntRange): Boolean = FAILURE.matchesIn(code, branch).any()

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

        /** The name of a precondition of the standard library or of a helper named like one. */
        val PRECONDITION = Regex("""(?:check|require)\w*""")

        /** The keyword `if`, followed by its condition in parentheses. */
        val IF = Regex("""\bif(?=[ \t]*\()""")

        /** A failure: a call of `fail` or `error`, not as the member of another value, or the keyword `throw`. */
        val FAILURE = Regex("""(?<![\w.])(?:fail|error)(?=[ \t]*\()|\bthrow\b""")
    }
}
