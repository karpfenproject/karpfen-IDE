package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Spec §11 item 3 for the parser, outline and index: the analyzers never throw on 10,000 deterministic mutations of
 * each language's examples (spread over its files) and on every prefix of a KStates example, and every outcome keeps
 * the documented invariants (ranges inside the text, outline nesting, sorted non-overlapping spans, multi-line folds,
 * names inside their quotes).
 */
class SyntaxRobustnessTest {

    @ParameterizedTest
    @EnumSource(KarpfenLanguage::class)
    fun `never throws on 10000 mutations of the examples`(language: KarpfenLanguage, reporter: TestReporter) {
        val files = Examples.files(language)
        val perFile = (MUTATIONS + files.size - 1) / files.size
        var checked = 0
        var broken = 0
        files.forEachIndexed { fileIndex, file ->
            Mutations.random(file.text, seed = SEED + fileIndex, count = perFile).forEach { mutation ->
                val analysis = try {
                    analyze(language, mutation.text)
                } catch (e: Exception) {
                    fail("${file.path}, $mutation threw $e", e)
                }
                checkInvariants(mutation.text, analysis, "${file.path}, $mutation")
                checkNameRanges(mutation.text, analysis, "${file.path}, $mutation")
                assertTrue(analysis.syntaxDiagnostics.all { it.source == DiagnosticSource.PARSER || it.source == DiagnosticSource.LEXER })
                checked++
                if (analysis.syntaxDiagnostics.isNotEmpty()) broken++
            }
        }
        assertTrue(checked >= MUTATIONS)
        reporter.publishEntry("$language mutations", "$checked analyzed over ${files.size} files, $broken with syntax errors")
    }

    @Test
    fun `never throws on any prefix of a KStates example`(reporter: TestReporter) {
        val text = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text
        var count = 0
        Mutations.prefixes(text).forEach { prefix ->
            val analysis = try {
                analyze(KarpfenLanguage.KSTATES, prefix)
            } catch (e: Exception) {
                fail("prefix of length ${prefix.length} threw $e", e)
            }
            checkInvariants(prefix, analysis, "prefix of length ${prefix.length}")
            checkNameRanges(prefix, analysis, "prefix of length ${prefix.length}")
            count++
        }
        assertTrue(count == text.length + 1)
        reporter.publishEntry("KStates prefixes", "$count prefixes analyzed")
    }

    private companion object {
        const val MUTATIONS = 10_000
        const val SEED = 20_261_020L
    }
}
