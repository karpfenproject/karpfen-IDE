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

import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Covers the latency of the language services on a 5,000-line KStates document (`SyntheticKstates.withMinLines(5000)`,
 * in the single-robot context): highlight, completion at a model path and among keywords, hover and definition at the
 * end of the document. There is no target of its own for core-lang; the measured values are reported through
 * [TestReporter] and guarded by the tokenizer's target for the same document size (30 ms) with 5x slack, so an
 * accidental quadratic algorithm would show.
 *
 * Tagged `budget`: excluded from every test run, because load and sleep/wake cycles make the wall clock unreliable;
 * kept for later performance work.
 */
@Tag("budget")
class LanguageServicesBudgetTest {

    private val probe = ServicesProbe()

    @Test
    fun `features on a 5000-line KStates document stay within the guard`(reporter: TestReporter) {
        val text = SyntheticKstates.withMinLines(LINES)
        val document = ExampleProject.document(ExampleProject.pathOf(ExamplePaths.ROBOT_KSTATES), text)
        val path = text.lastIndexOf("\$(target->") + "\$(target->".length
        val keywords = text.lastIndexOf("EVENT(\"public\", \"tick\")\n") + "EVENT(\"public\", \"tick\")\n".length
        val transition = text.lastIndexOf("TRANSITION \"s") + "TRANSITION \"s".length
        val measurements = mapOf(
            "highlight" to measure { probe.services.highlight(document) },
            "complete (model path)" to measure { probe.complete(document, path) },
            "complete (keywords)" to measure { probe.complete(document, keywords) },
            "complete (state names)" to measure { probe.complete(document, transition) },
            "hover (model path)" to measure { probe.hover(document, path - 3) },
            "definition (state)" to measure { probe.definition(document, transition) },
        )

        val lines = SyntheticKstates.lineCount(text)
        measurements.forEach { (feature, measured) ->
            reporter.publishEntry("$feature on $lines KStates lines", "measured $measured, guard $GUARD (asserted with ${SLACK}x slack)")
        }
        measurements.forEach { (feature, measured) -> assertTrue(measured < GUARD * SLACK, "$feature took $measured") }
        assertTrue(probe.complete(document, keywords).isNotEmpty() && probe.complete(document, transition).isNotEmpty())
    }

    /** The mean duration of [call] over the measured runs after the warm-up runs. */
    private fun measure(call: () -> Any?): Duration {
        repeat(WARM_UP) { call() }
        val mark = TimeSource.Monotonic.markNow()
        repeat(MEASURED) { call() }
        return mark.elapsedNow() / MEASURED
    }

    private companion object {
        const val LINES = 5_000
        const val WARM_UP = 10
        const val MEASURED = 20
        const val SLACK = 5
        val GUARD = 30.milliseconds
    }
}
