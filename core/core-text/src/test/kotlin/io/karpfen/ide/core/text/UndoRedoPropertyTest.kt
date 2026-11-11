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
import kotlin.test.assertNull
import kotlin.time.TestTimeSource

/**
 * Checks random edits interleaved with undo, redo, save points and clear after every operation. The reference is a
 * history in which every apply and every group is exactly one step. Typing is therefore preceded by `breakCoalescing`.
 */
class UndoRedoPropertyTest {

    /** The reference history. It keeps stacks of whole texts with state identities and the carets of each step. */
    private class ReferenceHistory(initial: String) {
        /** One undo step: the texts and carets before and after it, and the identity of the state it leads to. */
        class Step(val id: Int, val before: String, val after: String, val caretsBefore: List<CaretMemento>?, val caretsAfter: List<CaretMemento>?)

        private val undoSteps = ArrayList<Step>()
        private val redoSteps = ArrayList<Step>()
        private var nextId = 1
        private var baseId = 0
        private var savedId = 0
        var text = initial
            private set

        val canUndo get() = undoSteps.isNotEmpty()
        val canRedo get() = redoSteps.isNotEmpty()
        val isAtSavePoint get() = (undoSteps.lastOrNull()?.id ?: baseId) == savedId

        fun push(after: String, caretsBefore: List<CaretMemento>?, caretsAfter: List<CaretMemento>?) {
            undoSteps += Step(nextId++, text, after, caretsBefore, caretsAfter)
            redoSteps.clear()
            text = after
        }

        fun undo(): Step? = undoSteps.removeLastOrNull()?.also { redoSteps += it; text = it.before }

        fun redo(): Step? = redoSteps.removeLastOrNull()?.also { undoSteps += it; text = it.after }

        fun markSaved() {
            savedId = undoSteps.lastOrNull()?.id ?: baseId
        }

        fun clear() {
            baseId = undoSteps.lastOrNull()?.id ?: baseId
            undoSteps.clear()
            redoSteps.clear()
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [11, 12, 13])
    fun `interleaved undo and redo match the reference history`(seed: Long, reporter: TestReporter) {
        val random = Random(seed)
        val edits = RandomEdits(random)
        val initial = edits.text(30)
        val text = EditableText(initial, TestTimeSource())
        val reference = ReferenceHistory(initial)
        var undos = 0
        var redos = 0

        fun carets(length: Int) = if (random.nextInt(4) == 0) null else listOf(CaretMemento(edits.offset(length), edits.offset(length)))

        repeat(OPERATIONS) { operation ->
            when (random.nextInt(12)) {
                in 0..3 -> {
                    val list = edits.edits(reference.text.length, softLimit = 300)
                    val before = carets(reference.text.length)
                    val after = ReferenceText.applied(reference.text, list)
                    val afterCarets = carets(after.length)
                    if (random.nextBoolean()) text.undo.breakCoalescing()
                    val origin = if (random.nextBoolean()) EditOrigin.Typing else EditOrigin.Command("cmd")
                    if (origin == EditOrigin.Typing) text.undo.breakCoalescing()
                    if (text.apply(list, origin, before, afterCarets) != null) reference.push(after, before, afterCarets)
                }
                4 -> {
                    var current = reference.text
                    var first: List<CaretMemento>? = null
                    var last: List<CaretMemento>? = null
                    text.undo.group("insertions") {
                        repeat(1 + random.nextInt(3)) { index ->
                            val offset = edits.offset(current.length)
                            val inserted = edits.text(3).ifEmpty { "z" }
                            val before = carets(current.length)
                            val after = carets(current.length + inserted.length)
                            text.apply(listOf(TextEdit.insert(offset, inserted)), EditOrigin.Paste, before, after)
                            if (index == 0) first = before
                            last = after
                            current = current.substring(0, offset) + inserted + current.substring(offset)
                        }
                    }
                    reference.push(current, first, last)
                }
                in 5..7 -> {
                    val expected = reference.undo()
                    val result = text.undo.undo()
                    if (expected == null) assertNull(result) else {
                        undos++
                        assertEquals(expected.caretsBefore, result?.carets, "undo carets at operation $operation")
                    }
                }
                in 8..9 -> {
                    val expected = reference.redo()
                    val result = text.undo.redo()
                    if (expected == null) assertNull(result) else {
                        redos++
                        assertEquals(expected.caretsAfter, result?.carets, "redo carets at operation $operation")
                    }
                }
                10 -> {
                    text.undo.markSavePoint()
                    reference.markSaved()
                }
                else -> if (random.nextInt(10) == 0) {
                    text.undo.clear()
                    reference.clear()
                }
            }
            assertEquals(reference.text, text.snapshot.value.text, "text at operation $operation")
            assertEquals(reference.canUndo, text.undo.canUndo.value, "canUndo at operation $operation")
            assertEquals(reference.canRedo, text.undo.canRedo.value, "canRedo at operation $operation")
            assertEquals(reference.isAtSavePoint, text.undo.isAtSavePoint.value, "isAtSavePoint at operation $operation")
        }
        reporter.publishEntry("seed $seed", "$OPERATIONS operations, $undos undos, $redos redos")
    }

    private companion object {
        const val OPERATIONS = 10_000
    }
}
