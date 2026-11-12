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
package io.karpfen.ide.core.text

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * Performance budgets of core-text on a 5,000-line document, the size that the tokenize and parse budgets of
 * core-document use. Each budget is asserted with 5x slack, and the measured value is reported through
 * [TestReporter].
 *
 * The class is tagged `budget`, which the build excludes from every test run. Wall-clock time depends on machine load,
 * garbage collection and sleep cycles, so such assertions fail without a code change. The tests stay as executable
 * documentation of the performance targets.
 */
@Tag("budget")
class TextBudgetTest {

    private val document = buildString {
        repeat(LINES) { append("    state S").append(it).append(" { entry / counter = ").append(it).append(" }\n") }
    }

    private fun measure(repetitions: Int, block: () -> Unit): Duration {
        val mark = TimeSource.Monotonic.markNow()
        repeat(repetitions) { block() }
        return mark.elapsedNow() / repetitions
    }

    private fun check(reporter: TestReporter, name: String, measured: Duration, budget: Duration) {
        reporter.publishEntry(name, "measured $measured, budget $budget (asserted with 5x slack)")
        assertTrue(measured < budget * SLACK, "$name took $measured, budget $budget x $SLACK")
    }

    @Test
    fun `typing one character into a 5000-line text`(reporter: TestReporter) {
        val time = TestTimeSource()
        val text = EditableText(document, time)
        var caret = document.length / 2
        val keystroke = {
            text.apply(listOf(TextEdit.insert(caret, "a")), EditOrigin.Typing, listOf(CaretMemento.at(caret)), listOf(CaretMemento.at(caret + 1)))
            caret++
            time += 50.milliseconds
        }
        repeat(WARM_UP) { keystroke() }

        check(reporter, "keystroke (apply + undo record) in $LINES lines", measure(MEASURED) { keystroke() }, 500.microseconds)
    }

    @Test
    fun `mapping an offset and a range across 1000 versions`(reporter: TestReporter) {
        val text = EditableText(document)
        repeat(1_000) { text.apply(listOf(TextEdit.insert(it * 7, "x")), EditOrigin.Paste) }
        val oldest = text.snapshot.value.version - 1_000
        repeat(WARM_UP) { text.mapRange(TextRange(5_000, 6_000), oldest, text.lineage) }

        val offset = measure(MEASURED) { text.mapOffset(5_000, oldest) }
        val range = measure(MEASURED) { text.mapRange(TextRange(5_000, 6_000), oldest, text.lineage) }

        check(reporter, "mapOffset across 1000 versions", offset, 200.microseconds)
        check(reporter, "mapRange across 1000 versions", range, 400.microseconds)
    }

    /** One version as made by Select All and Tab, which is one action that inserts an indent at every line start. */
    @Test
    fun `mapping ranges across one version made of 10000 edits`(reporter: TestReporter) {
        val lines = 2 * LINES
        val text = EditableText(buildString { repeat(lines) { append("line ").append(it).append('\n') } })
        val index = LineIndex(text.snapshot.value.text)
        text.apply((0 until lines).map { TextEdit.insert(index.lineStart(it), "    ") }, EditOrigin.Command("indent"))
        val before = text.snapshot.value.version - 1
        val length = index.lineStart(lines - 1)
        var next = 0
        val mapping = {
            val start = (next * 7_919) % length
            next++
            text.mapRange(TextRange(start, start + 10), before, text.lineage)
        }
        repeat(WARM_UP) { mapping() }

        check(reporter, "mapRange across one version of $lines edits", measure(MEASURED) { mapping() }, 5.microseconds)
    }

    @Test
    fun `undo and redo of a step in a 5000-line text`(reporter: TestReporter) {
        val text = EditableText(document)
        repeat(STEPS) { text.apply(listOf(TextEdit.replace(TextRange(it * 40, it * 40 + 5), "edit$it")), EditOrigin.Command("edit")) }
        val edited = text.snapshot.value.text

        val undo = measure(STEPS) { text.undo.undo() }
        assertEquals(document, text.snapshot.value.text)
        val redo = measure(STEPS) { text.undo.redo() }
        assertEquals(edited, text.snapshot.value.text)

        check(reporter, "undo of one step in $LINES lines", undo, 1.milliseconds)
        check(reporter, "redo of one step in $LINES lines", redo, 1.milliseconds)
    }

    @Test
    fun `line index of a 5000-line text`(reporter: TestReporter) {
        repeat(20) { LineIndex(document).lineCount }

        val build = measure(20) { LineIndex(document) }
        val index = LineIndex(document)
        val query = measure(MEASURED) { index.positionOf(document.length / 3) }

        assertEquals(LINES + 1, index.lineCount)
        check(reporter, "LineIndex construction for $LINES lines", build, 2.milliseconds)
        check(reporter, "LineIndex.positionOf in $LINES lines", query, 2.microseconds)
    }

    private companion object {
        const val LINES = 5_000
        const val WARM_UP = 2_000
        const val MEASURED = 5_000
        const val STEPS = 1_000
        const val SLACK = 5
    }
}
