package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.services.ProviderFailure
import org.junit.jupiter.api.TestReporter
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the robustness of the environment descriptor features: highlight, completion, hover, definition, references
 * and bracket matching never throw, and no provider fails behind the facade's catch-all, on deterministic mutations of
 * every descriptor fixture and on every prefix of the valid one (at the typing position and one more offset); every
 * result lies inside the text.
 */
class EnvironmentServicesRobustnessTest {

    private val failures: MutableList<ProviderFailure> = Collections.synchronizedList(ArrayList())
    private val services = DescriptorFixture.services(failures)

    @Test
    fun `no feature fails on mutations of the descriptor fixtures`(reporter: TestReporter) {
        var count = 0
        for (name in DescriptorExamples.names()) {
            val example = DescriptorExamples.text(name)
            Mutations.random(example, seed = SEED + name.length, count = MUTATIONS).forEach { mutation ->
                val document = DescriptorFixture.document(mutation.text, version = mutation.index + 1L)
                val random = Random(SEED * 31 + mutation.index)
                val offsets = List(OFFSETS_PER_MUTATION) { random.nextInt(mutation.text.length + 1) } + mutation.text.length
                exercise(document, offsets, "$name, $mutation")
                count++
            }
        }
        reporter.publishEntry("descriptor mutations", "$count documents, ${OFFSETS_PER_MUTATION + 1} offsets each")
    }

    @Test
    fun `no feature fails on any prefix of a descriptor`() {
        val text = DescriptorFixture.VALID
        Mutations.prefixes(text).forEach { prefix ->
            val document = DescriptorFixture.document(prefix, version = prefix.length + 1L)
            val middle = if (prefix.isEmpty()) 0 else Random(prefix.length).nextInt(prefix.length + 1)
            exercise(document, listOf(prefix.length, middle), "prefix of length ${prefix.length}")
        }
    }

    /** Runs every feature on [document] at [offsets] and checks that results lie inside its text. */
    private fun exercise(document: LanguageDocument, offsets: List<Int>, label: String) {
        val length = document.text.length
        val spans = services.highlight(document)
        spans.zipWithNext().forEach { (a, b) -> check(a.range.end <= b.range.start) { "$label: unsorted highlight $a, $b" } }
        spans.forEach { check(it.range.end <= length && !it.range.isEmpty) { "$label: highlight $it" } }
        for (offset in offsets) {
            services.complete(document, offset).forEach { check(it.replaceRange.end <= length && it.replaceRange.start <= offset) { "$label @$offset: $it" } }
            services.hover(document, offset)?.let { check(it.range.end <= length) { "$label @$offset: $it" } }
            services.definition(document, offset).forEach { check(it.path != document.path) { "$label @$offset: $it" } }
            services.references(document, offset).forEach { check(it.path != document.path) { "$label @$offset: $it" } }
            services.matchingBracket(document, offset)?.let { check(it.open.end <= it.close.start && it.close.end <= length) { "$label @$offset: $it" } }
        }
        assertEquals(emptyList(), failures.map { "${it.operation}: ${it.error}" }, label)
    }

    private companion object {
        const val MUTATIONS = 2_000
        const val OFFSETS_PER_MUTATION = 6
        const val SEED = 20_261_024L
    }
}
