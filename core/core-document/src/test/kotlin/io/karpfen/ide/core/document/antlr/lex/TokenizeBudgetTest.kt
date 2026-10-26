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
package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.text.TextSnapshot
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The tokenize budget of the [io.karpfen.ide.core.document.syntax.Tokenizer] contract: a 5,000-line KStates document
 * tokenizes in < 30 ms, asserted with 5x slack; the
 * measured average after warm-up is reported through [TestReporter].
 *
 * Tagged `budget`, which the build excludes from every test run: wall-clock time depends on machine load, garbage
 * collection and sleep cycles, so such assertions fail without a code change. The test stays as executable
 * documentation for later performance work.
 */
@Tag("budget")
class TokenizeBudgetTest {

    @Test
    fun `a 5000-line KStates document tokenizes within budget`(reporter: TestReporter) {
        val text = SyntheticKstates.withMinLines(LINES)
        val tokenizer = KstatesTokenizer()
        var version = 0L
        repeat(WARM_UP) { tokenizer.tokenize(TextSnapshot(text, version++)) }

        val mark = TimeSource.Monotonic.markNow()
        var tokens = 0
        repeat(MEASURED) { tokens = tokenizer.tokenize(TextSnapshot(text, version++)).tokens.size }
        val average = mark.elapsedNow() / MEASURED

        reporter.publishEntry(
            "tokenize ${SyntheticKstates.lineCount(text)}-line KStates",
            "measured $average ($tokens tokens), budget $BUDGET (asserted with ${SLACK}x slack)",
        )
        assertTrue(average < BUDGET * SLACK, "tokenizing took $average, budget $BUDGET x $SLACK")
    }

    private companion object {
        const val LINES = 5_000
        const val WARM_UP = 30
        const val MEASURED = 30
        const val SLACK = 5
        val BUDGET = 30.milliseconds
    }
}
