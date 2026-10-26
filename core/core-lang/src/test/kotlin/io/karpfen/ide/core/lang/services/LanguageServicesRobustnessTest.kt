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
package io.karpfen.ide.core.lang.services

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.text.TextRange
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Covers the robustness of highlight, completion, definition and hover (references and bracket matching included): the
 * services never throw — and no provider fails behind the facade's catch-all — on 10,000 deterministic mutations of
 * each modeling language's examples (spread over its files, in their modeling contexts) and on every prefix of a
 * KStates example (environment descriptors have a robustness test of their own in the `environment` package); every
 * result keeps the documented invariants (ranges inside the text, highlight spans sorted and disjoint).
 */
class LanguageServicesRobustnessTest {

    private val failures = Collections.synchronizedList(ArrayList<ProviderFailure>())
    private val services: LanguageServices = ExampleProject.services(failures = failures)

    @ParameterizedTest
    @EnumSource(KarpfenLanguage::class, names = ["KMETA", "KMODEL", "KSTATES"])
    fun `no feature fails on 10000 mutations of the examples`(language: KarpfenLanguage, reporter: TestReporter) {
        val files = Examples.files(language)
        val perFile = (MUTATIONS + files.size - 1) / files.size
        val mark = TimeSource.Monotonic.markNow()
        var checked = 0
        files.forEachIndexed { fileIndex, file ->
            val path = ExampleProject.pathOf(file.path)
            Mutations.random(file.text, seed = SEED + fileIndex, count = perFile).forEach { mutation ->
                val document = ExampleProject.document(path, mutation.text, version = mutation.index + 1L)
                val random = Random(SEED * 31 + fileIndex * 100_003L + mutation.index)
                val offsets = List(OFFSETS_PER_MUTATION) { random.nextInt(mutation.text.length + 1) } + mutation.text.length
                exercise(document, offsets, "${file.path}, $mutation")
                checked++
            }
        }
        assertTrue(checked >= MUTATIONS)
        reporter.publishEntry("$language mutations", "$checked documents, ${OFFSETS_PER_MUTATION + 1} offsets each, ${mark.elapsedNow()}")
    }

    @Test
    fun `no feature fails on any prefix of a KStates example`(reporter: TestReporter) {
        val text = ExampleProject.textOf(ExamplePaths.ROBOT_KSTATES)
        val path = ExampleProject.pathOf(ExamplePaths.ROBOT_KSTATES)
        val mark = TimeSource.Monotonic.markNow()
        var count = 0
        Mutations.prefixes(text).forEach { prefix ->
            val document = ExampleProject.document(path, prefix, version = prefix.length.toLong())
            val middle = if (prefix.isEmpty()) 0 else Random(prefix.length).nextInt(prefix.length + 1)
            exercise(document, listOf(prefix.length, middle), "prefix of length ${prefix.length}")
            count++
        }
        assertEquals(text.length + 1, count)
        reporter.publishEntry("KStates prefixes", "$count prefixes, typing position and one random offset each, ${mark.elapsedNow()}")
    }

    /**
     * Runs every feature on [document] at [offsets] and checks the invariants of the results; [label] names the case.
     */
    private fun exercise(document: LanguageDocument, offsets: List<Int>, label: String) {
        val length = document.text.length
        val spans = services.highlight(document)
        spans.zipWithNext().forEach { (a, b) -> check(a.range.end <= b.range.start) { "$label: unsorted highlight $a, $b" } }
        spans.forEach { check(it.range.end <= length && !it.range.isEmpty) { "$label: highlight $it" } }
        for (offset in offsets) {
            services.complete(document, offset).forEach { check(it.replaceRange.end <= length) { "$label @$offset: $it" } }
            services.hover(document, offset)?.let { check(it.range.end <= length) { "$label @$offset: $it" } }
            services.definition(document, offset).forEach {
                check(isValid(document, it.path, it.version, it.range)) { "$label @$offset: $it" }
            }
            services.matchingBracket(document, offset)?.let {
                check(it.open.end <= length && it.close.end <= length) { "$label @$offset: $it" }
            }
        }
        services.references(document, offsets.first()).forEach { check(isValid(document, it.path, it.version, it.range)) { "$label: $it" } }
        check(failures.isEmpty()) { "$label: provider failures ${failures.map { "${it.operation}: ${it.error}" }}" }
    }

    /** A location of the document itself in its text's version lies inside its text. */
    private fun isValid(document: LanguageDocument, path: Path, version: Long?, range: TextRange): Boolean =
        path != document.path || version != document.text.version || range.end <= document.text.length

    private companion object {
        const val MUTATIONS = 10_000
        const val OFFSETS_PER_MUTATION = 6
        const val SEED = 20_261_021L
    }
}
