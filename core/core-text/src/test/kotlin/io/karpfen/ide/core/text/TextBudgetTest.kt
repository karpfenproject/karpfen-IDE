package io.karpfen.ide.core.text

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
 * Performance budgets of core-text on a 5,000-line document (the size of the tokenize/parse budgets of spec §3.4):
 * each is asserted with 5x slack and the measured value is reported through [TestReporter].
 */
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
