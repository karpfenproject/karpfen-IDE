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

import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Random edit sequences (typing with coalescing, multi-edit applies of every origin, groups, replaceAll) checked
 * against a `StringBuilder` reference after every operation, followed by undo-all and redo-all.
 */
class EditableTextPropertyTest {

    /** One run of random operations from [seed]: the text under test, its reference and the state of the run. */
    private class Session(seed: Long) {
        val random = Random(seed)
        val edits = RandomEdits(random)
        val time = TestTimeSource()
        val initial = edits.text(40)
        val text = EditableText(initial, time)
        val reference = ReferenceText(initial)

        /** The text after every applied change, starting with the initial text. */
        val history = mutableListOf(initial)
        var caret = 0
        var changes = 0
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 20261020])
    fun `random operations match the reference and undo-all and redo-all round-trip`(seed: Long, reporter: TestReporter) {
        val session = Session(seed)
        repeat(OPERATIONS) { session.randomOperation() }
        val final = session.text.snapshot.value.text
        assertEquals(session.reference.text, final)

        val undoSteps = session.undoAll()
        assertEquals(session.initial, session.text.snapshot.value.text, "undo-all returns the initial text")
        assertFalse(session.text.undo.canUndo.value)
        val redoSteps = session.redoAll()
        assertEquals(final, session.text.snapshot.value.text, "redo-all returns the final text")
        assertFalse(session.text.undo.canRedo.value)
        assertEquals(undoSteps, redoSteps)
        assertTrue(undoSteps in 1..session.changes, "steps $undoSteps for ${session.changes} changes")
        reporter.publishEntry("seed $seed", "$OPERATIONS operations, ${session.changes} changes, $undoSteps undo steps")
    }

    private fun Session.randomOperation() {
        time += if (random.nextInt(10) == 0) 1_500.milliseconds else random.nextInt(400).milliseconds
        val length = reference.text.length
        when (random.nextInt(20)) {
            in 0..6 -> {
                if (random.nextInt(5) == 0) caret = edits.offset(length)
                val char = edits.character()
                applyAndCheck(listOf(TextEdit.insert(caret.coerceAtMost(length), char)), EditOrigin.Typing)
                caret = caret.coerceAtMost(length) + char.length
            }
            in 7..8 -> if (caret in 1..length) {
                applyAndCheck(listOf(TextEdit.delete(caret - 1, caret)), EditOrigin.Typing)
                caret--
            }
            9 -> if (caret < length) applyAndCheck(listOf(TextEdit.delete(caret, caret + 1)), EditOrigin.Typing)
            in 10..14 -> applyAndCheck(edits.edits(length), ORIGINS[random.nextInt(ORIGINS.size)])
            15 -> replaceAllAndCheck()
            16 -> text.undo.group("random group") {
                repeat(1 + random.nextInt(3)) { applyAndCheck(edits.edits(reference.text.length, maxEdits = 2), EditOrigin.Command("grouped")) }
            }
            17 -> if (random.nextBoolean()) text.undo.breakCoalescing() else text.undo.markSavePoint()
            else -> {
                val versionBefore = text.snapshot.value.version
                assertNull(text.apply(listOf(TextEdit.insert(edits.offset(length), "")), EditOrigin.Typing))
                assertEquals(versionBefore, text.snapshot.value.version)
            }
        }
    }

    private fun Session.applyAndCheck(list: List<TextEdit>, origin: EditOrigin) {
        val before = text.snapshot.value
        val change = text.apply(list, origin)
        reference.apply(list)
        checkChange(before, change, origin)
    }

    private fun Session.replaceAllAndCheck() {
        val before = text.snapshot.value
        val target = ReferenceText.applied(reference.text, edits.edits(reference.text.length))
        val change = text.replaceAll(target, EditOrigin.External)
        reference.replaceAll(target)
        checkChange(before, change, EditOrigin.External)
        if (change != null) assertEquals(1, change.edits.size, "replaceAll is one minimal edit")
    }

    private fun Session.checkChange(before: TextSnapshot, change: TextChange?, origin: EditOrigin) {
        val current = text.snapshot.value
        assertEquals(reference.text, current.text)
        if (change == null) {
            assertSame(before, current, "a no-op produces no version")
            return
        }
        changes++
        history += current.text
        assertSame(before, change.before)
        assertSame(current, change.after)
        assertEquals(before.version + 1, current.version)
        assertSame(text.lineage, current.lineage)
        assertEquals(origin, change.origin)
        assertEquals(current.text, ReferenceText.applied(before.text, change.edits), "the change's edits describe it")
    }

    /** Undoes every step; every state reached is an earlier state of the history. Returns the number of steps. */
    private fun Session.undoAll(): Int {
        var position = history.lastIndex
        var steps = 0
        while (true) {
            val result = text.undo.undo() ?: break
            steps++
            val current = text.snapshot.value.text
            assertEquals(current, ReferenceText.applied(result.change.before.text, result.change.edits))
            position = (position - 1 downTo 0).firstOrNull { history[it] == current }
                ?: error("undo reached a state that is not an earlier state: '$current'")
        }
        return steps
    }

    private fun Session.redoAll(): Int {
        var steps = 0
        while (true) {
            val result = assertNotNull(text.undo.redo() ?: return steps)
            steps++
            assertEquals(EditOrigin.Redo, result.change.origin)
        }
    }

    private companion object {
        const val OPERATIONS = 10_000
        val ORIGINS = listOf(
            EditOrigin.Typing, EditOrigin.Paste, EditOrigin.Cut, EditOrigin.External,
            EditOrigin.Command("cmd"), EditOrigin.Programmatic("gen"),
        )
    }
}
