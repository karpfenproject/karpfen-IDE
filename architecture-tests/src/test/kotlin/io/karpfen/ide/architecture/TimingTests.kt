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

import com.lemonappdev.konsist.api.declaration.KoAnnotationDeclaration
import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.provider.KoAnnotationProvider
import com.lemonappdev.konsist.api.provider.KoContainingDeclarationProvider

/**
 * What the budget tripwire finds out about one test function.
 *
 * @property file the path of the test source file.
 * @property test the name of the test function.
 * @property isTaggedBudget true if the function, or a class it is declared in, carries the JUnit tag `budget`.
 * @property assertsOnWallClock true if the function asserts on a wall-clock measurement ([WallClockAssertions]) or runs
 *   under a tight JUnit `@Timeout` ([TimeLimits]).
 */
internal data class TimingVerdict(val file: String, val test: String, val isTaggedBudget: Boolean, val assertsOnWallClock: Boolean) {
    override fun toString(): String = "$file: $test"
}

/**
 * Reads the test functions of a test source file for the budget tripwire. Konsist supplies the declarations: every
 * function of the file, nested and local ones included, goes to [WallClockAssertions], which follows measurements and
 * assertions through the helpers of the file, and every test function, a function annotated as a JUnit test, gets a
 * [TimingVerdict].
 *
 * The annotations are looked up per test, on the function itself and on the classes around it, so a file that mixes
 * tagged and untagged tests is judged test by test. They are read by their simple name and their arguments, so the
 * tag counts as `@Tag("budget")`, `@Tag(value = "budget")`, fully qualified or inside JUnit's container `@Tags`, and
 * JUnit's `@Timeout` counts with its value and unit.
 */
internal object TimingTests {

    /** The JUnit tag of time-budget tests, the value of `TestTags.BUDGET` in build-logic. */
    const val BUDGET_TAG = "budget"

    /** The simple names of the JUnit annotations that make a function a test. */
    private val TEST_ANNOTATIONS = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")

    /** A tag annotation with the budget tag as an argument of JUnit's container `@Tags`. */
    private val NESTED_BUDGET_TAG = Regex("""^(?:[\w.]+\.)?Tag\(\s*(?:value\s*=\s*)?"$BUDGET_TAG"\s*\)$""")

    /** The verdicts on the test functions of [file], in the order of their declarations. */
    fun verdicts(file: KoFileDeclaration): List<TimingVerdict> {
        val functions = file.functions(includeNested = true, includeLocal = true)
        val assertions = WallClockAssertions(functions.map { it.source })
        return functions.filter { it.isTest }.map { function ->
            val assertsOnWallClock = assertions.assertsOnWallClock(function.source) || function.carries(::isTightTimeout)
            TimingVerdict(file.path, function.name, function.carries(::isBudgetTag), assertsOnWallClock)
        }
    }

    /** The function as [WallClockAssertions] reads it. */
    private val KoFunctionDeclaration.source: FunctionSource get() = FunctionSource(name, text)

    /** True if the function carries one of the [TEST_ANNOTATIONS]. */
    private val KoFunctionDeclaration.isTest: Boolean get() = annotations.any { it.name in TEST_ANNOTATIONS }

    /** True if the function or one of the declarations around it, up to the file, carries an annotation that [matches]. */
    private fun KoFunctionDeclaration.carries(matches: (KoAnnotationDeclaration) -> Boolean): Boolean =
        generateSequence<KoBaseDeclaration>(this) { (it as? KoContainingDeclarationProvider)?.containingDeclaration }
            .takeWhile { it !is KoFileDeclaration }
            .any { declaration -> (declaration as? KoAnnotationProvider)?.annotations?.any(matches) == true }

    /** True if [annotation] is JUnit's tag annotation with the budget tag, alone or inside the container `@Tags`. */
    private fun isBudgetTag(annotation: KoAnnotationDeclaration): Boolean = when (annotation.name) {
        "Tag" -> annotation.arguments.any { it.value == BUDGET_TAG }
        "Tags" -> annotation.arguments.any { NESTED_BUDGET_TAG.matches(it.value?.trim().orEmpty()) }
        else -> false
    }

    /**
     * True if [annotation] is JUnit's `@Timeout` with a tight limit. Its value is the argument named `value` or the
     * first one, its unit the argument named `unit` or the second one.
     */
    private fun isTightTimeout(annotation: KoAnnotationDeclaration): Boolean {
        if (annotation.name != "Timeout") return false
        val arguments = annotation.arguments
        val value = arguments.firstOrNull { it.name == "value" } ?: arguments.firstOrNull { it.name.isEmpty() }
        val unit = arguments.firstOrNull { it.name == "unit" } ?: arguments.filter { it.name.isEmpty() }.getOrNull(1)
        return TimeLimits.isTightJUnitTimeout(value?.value ?: return false, unit?.value)
    }
}
