package io.karpfen.ide.core.text

import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * `mapOffset` (both biases) and `mapRange` on the real text against the character-identity oracle of [ReferenceText],
 * across random edits, groups, replaceAll, undo and redo, including evicted history.
 */
class OffsetMappingPropertyTest {

    /** A range tracked from [fromVersion]: its bounds followed with the contract's biases. */
    private class TrackedRange(val fromVersion: Long, val range: TextRange, val start: ReferenceText.Tracker, val end: ReferenceText.Tracker?) {
        /** The range the contract expects now: start RIGHT, end LEFT, inverted → empty at the end, empty stays empty. */
        val expected: TextRange
            get() {
                val mappedEnd = end ?: return TextRange.empty(start.offset)
                return if (start.offset <= mappedEnd.offset) TextRange(start.offset, mappedEnd.offset) else TextRange.empty(mappedEnd.offset)
            }
    }

    /** An offset mapped to an intermediate version: (from, original, bias) → [expected] at version [atVersion]. */
    private class Checkpoint(val fromVersion: Long, val original: Int, val bias: Bias, val atVersion: Long, val expected: Int)

    @ParameterizedTest
    @ValueSource(longs = [31, 32, 33])
    fun `offsets and ranges map like the reference across random edits, undo and redo`(seed: Long, reporter: TestReporter) {
        val random = Random(seed)
        val edits = RandomEdits(random)
        val initial = edits.text(40)
        val text = EditableText(initial, TestTimeSource(), historyLimit = HISTORY)
        val reference = ReferenceText(initial)
        val ranges = ArrayList<TrackedRange>()
        val checkpoints = ArrayList<Checkpoint>()
        var mapped = 0
        var evicted = 0
        var collapsed = 0
        var empty = 0

        repeat(OPERATIONS) {
            val change = when (random.nextInt(10)) {
                in 0..4 -> edits.edits(reference.text.length, softLimit = 200).let { list ->
                    text.apply(list, if (random.nextBoolean()) EditOrigin.Typing else EditOrigin.Paste)?.also { reference.apply(list) }
                }
                5 -> text.replaceAll(ReferenceText.applied(reference.text, edits.edits(reference.text.length, softLimit = 200)), EditOrigin.External)
                    ?.also { reference.apply(it.edits) }
                6 -> {
                    var last: TextChange? = null
                    text.undo.group("g") {
                        repeat(1 + random.nextInt(3)) {
                            val list = edits.edits(reference.text.length, maxEdits = 2, softLimit = 200)
                            text.apply(list, EditOrigin.Command("c"))?.also { reference.apply(list); last = it }
                        }
                    }
                    last
                }
                in 7..8 -> text.undo.undo()?.change?.also { reference.apply(it.edits) }
                else -> text.undo.redo()?.change?.also { reference.apply(it.edits) }
            }
            val current = text.snapshot.value
            assertEquals(reference.text, current.text)
            if (change == null) return@repeat

            // Check every tracked offset, range and checkpoint against the oracle.
            for (tracker in reference.tracked) {
                val result = text.mapOffset(tracker.original, tracker.fromVersion, bias = tracker.bias)
                if (current.version - tracker.fromVersion > HISTORY) {
                    assertNull(result, "evicted history")
                    evicted++
                } else {
                    assertEquals(tracker.offset, result, "${tracker.bias} ${tracker.original} from ${tracker.fromVersion} to ${current.version}")
                    mapped++
                }
            }
            for (range in ranges) {
                val result = text.mapRange(range.range, range.fromVersion, text.lineage)
                if (current.version - range.fromVersion > HISTORY) {
                    assertNull(result)
                } else {
                    assertEquals(range.expected, result, "range ${range.range}")
                    if (range.range.isEmpty) empty++ else if (range.start.offset > range.end!!.offset) collapsed++
                }
            }
            for (checkpoint in checkpoints) {
                val result = text.mapOffset(checkpoint.original, checkpoint.fromVersion, checkpoint.atVersion, checkpoint.bias)
                if (current.version - checkpoint.fromVersion > HISTORY) assertNull(result) else assertEquals(checkpoint.expected, result)
            }

            // Retire old trackers and start new ones at this version.
            reference.untrack { current.version - it.fromVersion > HISTORY + 5 }
            ranges.removeAll { current.version - it.fromVersion > HISTORY + 5 }
            checkpoints.removeAll { current.version - it.fromVersion > HISTORY + 5 }
            reference.tracked.filter { random.nextInt(40) == 0 }.forEach {
                checkpoints += Checkpoint(it.fromVersion, it.original, it.bias, current.version, it.offset)
            }
            reference.track(current.version, edits.offset(current.length), Bias.entries[random.nextInt(2)])
            if (random.nextBoolean()) return@repeat
            val range = if (random.nextInt(4) == 0) TextRange.empty(edits.offset(current.length)) else edits.range(current.length)
            ranges += TrackedRange(
                current.version,
                range,
                reference.track(current.version, range.start, Bias.RIGHT),
                if (range.isEmpty) null else reference.track(current.version, range.end, Bias.LEFT),
            )
        }
        assertTrue(collapsed > 0 && empty > 0 && evicted > 0, "every case of the range rule was exercised")
        reporter.publishEntry(
            "seed $seed",
            "$OPERATIONS operations, $mapped offsets checked, $evicted evicted lookups, $collapsed inverted ranges collapsed, $empty empty ranges",
        )
    }

    @Test
    fun `mapRange never throws and answers null for other lineages, newer versions and evicted history`() {
        val random = Random(77)
        val edits = RandomEdits(random)
        val text = EditableText(edits.text(20), historyLimit = 8)
        val foreign = TextLineage()
        repeat(OPERATIONS) {
            text.apply(edits.edits(text.snapshot.value.length, softLimit = 100), EditOrigin.Paste)
            val current = text.snapshot.value.version
            val from = random.nextLong(-3, current + 4)
            val to = random.nextLong(-3, current + 4)
            val range = edits.range(text.snapshot.value.length + 10)

            val result = text.mapRange(range, from, text.lineage, to)
            val expectedNull = from < 0 || from > to || to > current || from < current - 8
            if (expectedNull) assertNull(result, "from $from to $to at $current") else requireNotNull(result)
            assertNull(text.mapRange(range, from.coerceAtLeast(0), foreign, current), "another lineage")
            assertNull(text.mapRange(range, TextSnapshot(text.snapshot.value.text, current)), "a snapshot created on its own")
        }
    }

    private companion object {
        const val OPERATIONS = 10_000
        const val HISTORY = 24
    }
}
