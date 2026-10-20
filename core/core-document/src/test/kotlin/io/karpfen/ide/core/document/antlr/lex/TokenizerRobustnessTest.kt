package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.ExampleFile
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.document.syntax.TokenKind
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Spec §11 item 3 for the tokenizers: they never throw and keep every token invariant ([assertTokenInvariants]) on
 * 10,000 deterministic mutations of every example and on every prefix of one KStates example.
 */
class TokenizerRobustnessTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    fun `10,000 mutations of every example`(file: ExampleFile, reporter: TestReporter) {
        val mark = TimeSource.Monotonic.markNow()
        var lexerErrors = 0
        var errorTokens = 0
        for (mutation in Mutations.random(file.text, seed = SEED, count = MUTATIONS)) {
            val result = tokenizeChecked(file.language, mutation.text, "$file, $mutation")
            if (result.diagnostics.any { it.source == DiagnosticSource.LEXER }) lexerErrors++
            if (result.tokens.any { it.kind == TokenKind.ERROR }) errorTokens++
        }
        reporter.publishEntry(
            file.path,
            "$MUTATIONS mutations (seed $SEED) in ${mark.elapsedNow()}: $lexerErrors with lexer errors, " +
                "$errorTokens with error tokens",
        )
    }

    @Test
    fun `every prefix of a KStates example`(reporter: TestReporter) {
        val file = prefixExample()
        val mark = TimeSource.Monotonic.markNow()

        val prefixes = Mutations.prefixes(file.text).onEach { prefix ->
            tokenizeChecked(KarpfenLanguage.KSTATES, prefix, "$file, prefix of length ${prefix.length}")
        }.count()

        reporter.publishEntry(file.path, "$prefixes prefixes in ${mark.elapsedNow()}")
    }

    /** The largest KStates example (most EVAL bodies, comments and nested blocks), so prefixes cut through all of them. */
    private fun prefixExample(): ExampleFile = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates")

    companion object {
        private const val SEED = 20_261_020L
        private const val MUTATIONS = 10_000

        /** Every example file (the parameters of the mutation test). */
        @JvmStatic
        fun examples(): List<ExampleFile> = Examples.files()
    }
}
