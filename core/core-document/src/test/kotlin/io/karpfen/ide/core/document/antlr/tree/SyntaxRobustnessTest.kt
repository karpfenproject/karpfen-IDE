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
 * Covers the robustness of the parser, outline and index of the modeling languages (environment descriptors have tests
 * of their own in the `environment` package): the analyzers never throw on 10,000 deterministic mutations of
 * each language's examples (spread over its files) and on every prefix of a KStates example, and every outcome keeps
 * the documented invariants (ranges inside the text, outline nesting, sorted non-overlapping spans, multi-line folds,
 * names inside their quotes).
 */
class SyntaxRobustnessTest {

    @ParameterizedTest
    @EnumSource(KarpfenLanguage::class, names = ["KMETA", "KMODEL", "KSTATES"])
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
