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
package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.text.TextSnapshot
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The parse budget of the [DocumentParser] contract. A 5,000-line KStates document should parse in less than 150 ms
 * in the default mode, which is syntax only. The test asserts this with 5x slack and reports the measured value
 * through [TestReporter].
 *
 * The test is tagged `budget`, which the build excludes from every test run. Wall-clock time depends on machine load,
 * garbage collection and sleep cycles, so such assertions fail without a code change. The test stays as executable
 * documentation for later performance work.
 */
@Tag("budget")
class ParseBudgetTest {

    @Test
    fun `a 5000-line KStates document parses within the budget`(reporter: TestReporter) {
        val text = SyntheticKstates.withMinLines(LINES)
        val parser: DocumentParser = ComposedDocumentParser(KarpfenLanguage.KSTATES, KstatesSyntaxAnalyzer(), semantics = null)
        val snapshots = List(WARM_UP + MEASURED) { TextSnapshot(text, it.toLong()) }
        snapshots.take(WARM_UP).forEach { check(parser.parse(it).isSyntaxClean) }

        val mark = TimeSource.Monotonic.markNow()
        snapshots.drop(WARM_UP).forEach { parser.parse(it) }
        val measured: Duration = mark.elapsedNow() / MEASURED

        val lines = SyntheticKstates.lineCount(text)
        reporter.publishEntry("parse of $lines KStates lines", "measured $measured, budget $BUDGET (asserted with ${SLACK}x slack)")
        assertTrue(measured < BUDGET * SLACK, "parsing $lines lines took $measured, budget $BUDGET x $SLACK")
    }

    private companion object {
        const val LINES = 5_000
        const val WARM_UP = 10
        const val MEASURED = 20
        const val SLACK = 5
        val BUDGET = 150.milliseconds
    }
}
