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
 * @property isTaggedBudget true if the function, or a class it is declared in, carries `@Tag("budget")`.
 * @property assertsOnWallClock true if the function asserts on a wall-clock measurement ([WallClockAssertions]).
 */
internal data class TimingVerdict(val file: String, val test: String, val isTaggedBudget: Boolean, val assertsOnWallClock: Boolean) {
    override fun toString(): String = "$file: $test"
}

/**
 * Reads the test functions of a test source file for the budget tripwire. Konsist supplies the declarations: every
 * function of the file, nested and local ones included, goes to [WallClockAssertions], which follows measurements and
 * assertions through the helpers of the file, and every test function, a function annotated as a JUnit test, gets a
 * [TimingVerdict]. The tag is looked up per test, on the function itself and on the classes around it, so a file that
 * mixes tagged and untagged tests is judged test by test.
 */
internal object TimingTests {

    /** The simple names of the JUnit annotations that make a function a test. */
    private val TEST_ANNOTATIONS = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")

    /** The text of the tag annotation with the budget tag, `@Tag("budget")`, blanks allowed inside the parentheses. */
    private val BUDGET_TAG = Regex("""^@Tag\(\s*"budget"\s*\)$""")

    /** The verdicts on the test functions of [file], in the order of their declarations. */
    fun verdicts(file: KoFileDeclaration): List<TimingVerdict> {
        val functions = file.functions(includeNested = true, includeLocal = true)
        val assertions = WallClockAssertions(functions.map { it.source })
        return functions.filter { it.isTest }.map { function ->
            TimingVerdict(file.path, function.name, function.isTaggedBudget, assertions.assertsOnWallClock(function.source))
        }
    }

    /** The function as [WallClockAssertions] reads it. */
    private val KoFunctionDeclaration.source: FunctionSource get() = FunctionSource(name, text)

    /** True if the function carries one of the [TEST_ANNOTATIONS]. */
    private val KoFunctionDeclaration.isTest: Boolean get() = annotations.any { it.name in TEST_ANNOTATIONS }

    /** True if the function or one of the declarations around it, up to the file, carries the budget tag. */
    private val KoFunctionDeclaration.isTaggedBudget: Boolean
        get() = generateSequence<KoBaseDeclaration>(this) { (it as? KoContainingDeclarationProvider)?.containingDeclaration }
            .takeWhile { it !is KoFileDeclaration }
            .any { declaration -> (declaration as? KoAnnotationProvider)?.annotations?.any(::isBudgetTag) == true }

    /** True if [annotation] is the tag annotation with the budget tag. */
    private fun isBudgetTag(annotation: KoAnnotationDeclaration): Boolean = BUDGET_TAG.matches(annotation.text.trim())
}
