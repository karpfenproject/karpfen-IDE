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
 * The values in the code of one function that hold a wall-clock measurement, found by following the wall clock from
 * where it is read through the names it is bound to.
 *
 * Two kinds of value are told apart. A timestamp is a reading of the wall clock: a mark of the monotonic time source
 * (`TimeSource.Monotonic`, also imported as `Monotonic`), `System.nanoTime()`, `System.currentTimeMillis()`,
 * `Clock.System` or `Instant.now()`, and a value computed from one, such as a deadline `mark + 10.seconds`. A
 * measurement is a duration measured on the wall clock:
 *
 * - A call of a measuring function of the Kotlin standard library (`measureTime`, `measureTimedValue`,
 *   `measureNanoTime`, `measureTimeMillis`). It counts unqualified, qualified by its package or called on the
 *   monotonic source, but not when called on another receiver, such as a test time source.
 * - `elapsedNow()` called on a timestamp.
 * - A subtraction with a timestamp as one of its operands, such as `System.nanoTime() - start`, and a `between` call
 *   whose arguments hold a timestamp, such as java.time's `Duration.between(start, Instant.now())`.
 * - A call of one of the [measuringFunctions] of the same file.
 * - A value computed from a measurement.
 *
 * A timestamp alone measures nothing. Comparing it with a stored one, saving it, or checking a deadline with
 * `hasNotPassedNow()` or with a comparison against a fresh clock reading asserts nothing on a duration.
 *
 * A name holds the kind of value that the code giving it its value mentions ([Binding]):
 *
 * - A `val` or `var`, destructuring declarations included, takes its value from its initializer up to the end of the
 *   statement. So `val average = mark.elapsedNow() / runs` binds `average` to a measurement.
 * - A name assigned at the start of a statement takes its value from the assigned value, compound assignments
 *   included. So both `elapsed = measureTime { … }` and `total += measureTime { … }` bind.
 * - The parameters of a lambda, `it` included, take their values from the receiver and the argument list of the call
 *   that takes the lambda ([Lambdas]). So `measurements.forEach { (name, duration) -> … }` binds `name` and `duration`
 *   once `measurements` holds measurements, and `measureTime { … }.let { … }` binds `it`.
 * - The variables of a `for` loop take their values from the expression after `in`.
 *
 * The parameters of a lambda hold their value inside the lambda only, where they hide the names of the same name
 * outside it. So `it` means the parameter of the innermost lambda around it. Every other name holds its value in all of
 * the function. The rule reads [code] as text and knows neither types nor the scopes of local declarations. A value
 * passed on through a function parameter, a property or a lambda parameter with a type is lost. Names are followed
 * until no more are bound.
 *
 * @property code the code of the function, comments and string literals blanked ([KotlinLexer.codeOutsideStrings]).
 * @param measuringFunctions the names of the functions of the same file that return a measurement.
 */
internal class MeasuredValues(private val code: String, measuringFunctions: Collection<String>) {

    /** The calls of the functions that return a measurement. */
    private val measuringCalls = FunctionCalls(measuringFunctions)

    /** Every place in [code] that binds names. */
    private val bindings: List<Binding> = declarations() + assignments() + lambdaParameters() + loopVariables()

    /** The bindings of each name. */
    private val bindingsByName: Map<String, List<Binding>> =
        bindings.flatMap { binding -> binding.names.map { it to binding } }.groupBy({ it.first }, { it.second })

    /** A mention of a bound name that is not the member of another value. Null if no name is bound. */
    private val boundName: Regex? =
        bindingsByName.keys.takeIf { it.isNotEmpty() }?.let { Regex("""(?<![\w.])(?:${it.joinToString("|")})\b""") }

    /** What the bindings hold, followed until no more are bound. */
    private val known: Knowledge = knowledge()

    /** True if [region], a range of [code], holds a measurement. */
    fun areMentionedIn(region: IntRange): Boolean = known.measures(region)

    /** The bindings that hold a measurement or a timestamp, grown from none until no more are bound. */
    private fun knowledge(): Knowledge {
        var step = Knowledge(emptySet(), emptySet())
        while (true) {
            val next = Knowledge(
                bindings.filter { binding -> binding.sources.any(step::measures) }.toSet(),
                bindings.filter { binding -> binding.sources.any(step::stamps) }.toSet(),
            )
            if (next.measurements == step.measurements && next.timestamps == step.timestamps) return step
            step = next
        }
    }

    /**
     * What is known at one step of [knowledge]. Each set only grows from step to step, because a region mentions a
     * measurement or a timestamp whenever it did with fewer bindings known, so the steps end.
     *
     * @property measurements the bindings known to hold a measurement.
     * @property timestamps the bindings known to hold a timestamp.
     */
    private inner class Knowledge(val measurements: Set<Binding>, val timestamps: Set<Binding>) {

        /** True if [region] holds a measurement, as the class documentation defines it. */
        fun measures(region: IntRange): Boolean =
            MEASURING_CALL.matchesIn(code, region).any() ||
                measuringCalls.areCalledIn(code, region) ||
                mentionsBound(region, measurements) ||
                ELAPSED.matchesIn(code, region).any { stamps(receiverBefore(it.range.first)) } ||
                MINUS.matchesIn(code, region).any { subtractsTimestamp(it.range.first) } ||
                BETWEEN.matchesIn(code, region).any { stamps(CodeRegions.argumentList(code, it.range.last + 1)) }

        /** True if [region] holds a timestamp. */
        fun stamps(region: IntRange): Boolean = WALL_CLOCK_READ.matchesIn(code, region).any() || mentionsBound(region, timestamps)

        /** The receiver of the member access that starts at [access]. */
        private fun receiverBefore(access: Int): IntRange = CodeRegions.startOfOperandBefore(code, access) until access

        /** True if the minus at [minus] subtracts two operands, one of which holds a timestamp. A unary minus does not. */
        private fun subtractsTimestamp(minus: Int): Boolean {
            val left = CodeRegions.startOfOperandBefore(code, minus)
            return left < minus && (stamps(left until minus) || stamps(minus + 1 until CodeRegions.endOfOperandAfter(code, minus + 1)))
        }

        /** True if [region] mentions a name whose binding at the place of the mention is one of [bound]. */
        private fun mentionsBound(region: IntRange, bound: Set<Binding>): Boolean =
            boundName?.matchesIn(code, region)?.any { mention ->
                bindingsAt(mention.value, mention.range.first).any { it in bound }
            } ?: false
    }

    /**
     * The bindings that give [name] its value at [offset]: the parameter of the innermost lambda around [offset] that has
     * one of that name, or else every binding of the name outside lambda parameters.
     */
    private fun bindingsAt(name: String, offset: Int): List<Binding> {
        val candidates = bindingsByName.getValue(name)
        val innermost = candidates.filter { it.scope?.contains(offset) == true }.maxByOrNull { it.scope!!.first }
        return if (innermost != null) listOf(innermost) else candidates.filter { it.scope == null }
    }

    /** The `val` and `var` declarations, bound by their initializers. */
    private fun declarations(): List<Binding> = DECLARATION.findAll(code).map { match ->
        val names = identifiersIn(match.groupValues[1].ifEmpty { match.groupValues[2] })
        Binding(names, listOf(CodeRegions.statementFrom(code, match.range.last + 1)))
    }.toList()

    /** The assignments that start a statement outside parentheses and brackets, bound by the assigned values. */
    private fun assignments(): List<Binding> = ASSIGNMENT.findAll(code)
        .filter { CodeRegions.enclosingBracket(code, it.groups[1]!!.range.first).let { bracket -> bracket == null || bracket == '{' } }
        .map { match -> Binding(listOf(match.groupValues[1]), listOf(CodeRegions.statementFrom(code, match.range.last + 1))) }
        .toList()

    /** The parameters of the lambdas, bound by the inputs of the lambdas and holding their values inside them. */
    private fun lambdaParameters(): List<Binding> =
        Lambdas.findIn(code).filter { it.parameters.isNotEmpty() }.map { Binding(it.parameters, it.inputs, it.body) }

    /** The variables of the `for` loops, bound by the expressions after `in`. */
    private fun loopVariables(): List<Binding> = FOR_LOOP.findAll(code).map { match ->
        val header = match.range.last + 1 until CodeRegions.endOfBracket(code, match.range.first + match.value.indexOf('(')) - 1
        Binding(identifiersIn(match.groupValues[1]), listOf(header))
    }.toList()

    /**
     * Names and the code that gives them their value. A binding is an identity: two places that bind the same names
     * are two bindings.
     *
     * @property names the names bound.
     * @property sources the regions of [code] whose value they hold.
     * @property scope the region in which the names hold that value, which is the body of a lambda for its parameters.
     *   Null means all of the function.
     */
    private class Binding(val names: List<String>, val sources: List<IntRange>, val scope: IntRange? = null)

    private companion object {
        /** A call of a measuring function of the Kotlin standard library, as the class documentation lists them. */
        val MEASURING_CALL = Regex(
            """(?<![\w.])(?:kotlin\.(?:system|time)\.)?(?:measureTimedValue|measureTime|measureNanoTime|measureTimeMillis)\b|""" +
                """(?<![\w.])(?:kotlin\.time\.)?(?:TimeSource\.)?Monotonic\.(?:measureTimedValue|measureTime)\b""",
        )

        /** A reading of the wall clock: the monotonic time source, the system clock or a date-time clock. */
        val WALL_CLOCK_READ = Regex(
            """(?<![\w.])(?:kotlin\.time\.)?(?:TimeSource\.)?Monotonic\b|\bSystem\.(?:nanoTime|currentTimeMillis)\b|""" +
                """\bClock\.System\b|\bInstant\.now\b""",
        )

        /** The member access of `elapsedNow`, plain or as a safe call. Its receiver ends where the match starts. */
        val ELAPSED = Regex("""\??\.elapsedNow\b""")

        /** A minus that is no part of an arrow, a decrement, a compound assignment or another operator. */
        val MINUS = Regex("""(?<![-+*/%=<>!&|])-(?![-=>])""")

        /** The name of a `between` call, followed by its argument list. */
        val BETWEEN = Regex("""\bbetween(?=[ \t]*\()""")

        /**
         * A `val` or `var` with its name, or its destructured names in parentheses, an optional type and the equals
         * sign of its initializer. Group one holds destructured names, group two a single name.
         */
        val DECLARATION = Regex("""\b(?:val|var)\s+(?:\(([^()]*)\)|(\w+))(?:\s*:[^=\n]*)?\s*=(?!=)""")

        /**
         * A name at the start of a statement, at the start of a line or after a semicolon, an opening brace or an
         * arrow, followed by an assignment operator. Group one holds the name.
         */
        val ASSIGNMENT = Regex("""(?:^|[;{]|->)[ \t]*(\w+)[ \t]*[-+*/%]?=(?!=)""", RegexOption.MULTILINE)

        /** The head of a `for` loop up to `in`. Group one holds its variables, plain or destructured. */
        val FOR_LOOP = Regex("""\bfor\s*\(\s*([\w\s,()]+?)\s+in\b""")

        /** A plain identifier. [identifiersIn] leaves out the underscore that skips a destructured value. */
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

        /** The identifiers in [text], a list of names, without the placeholder underscore. */
        fun identifiersIn(text: String): List<String> = IDENTIFIER.findAll(text).map { it.value }.filter { it != "_" }.toList()
    }
}
