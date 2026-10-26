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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the composition of two edit scripts into one (`EditScript.then`): merged typing, changes that cancel out,
 * distant edits, rejection of a second script that reaches too far, and agreement with applying both scripts on random
 * texts and chains.
 */
class EditCompositionTest {

    @Test
    fun `typing after typing becomes one insertion`() {
        val first = EditScript.of(listOf(TextEdit.insert(3, "a")), 5)
        val second = EditScript.of(listOf(TextEdit.insert(4, "b")), 6)

        assertEquals(listOf(TextEdit.insert(3, "ab")), first.then(second, 5).edits)
    }

    @Test
    fun `changes that cancel out compose to the empty script`() {
        val insert = EditScript.of(listOf(TextEdit.insert(2, "xy")), 4)
        val delete = EditScript.of(listOf(TextEdit.delete(2, 4)), 6)

        assertTrue(insert.then(delete, 4).isEmpty)
    }

    @Test
    fun `edits far apart stay separate edits`() {
        val first = EditScript.of(listOf(TextEdit.insert(0, "#"), TextEdit.insert(10, "#")), 20)
        val second = EditScript.of(listOf(TextEdit.delete(21, 22)), 22)

        assertEquals(listOf(TextEdit.insert(0, "#"), TextEdit.insert(10, "#"), TextEdit.delete(19, 20)), first.then(second, 20).edits)
    }

    @Test
    fun `a second script reaching past the first script's result is rejected`() {
        val first = EditScript.of(listOf(TextEdit.delete(0, 2)), 4)

        assertFailsWith<IllegalArgumentException> { first.then(EditScript.of(listOf(TextEdit.delete(1, 3)), 3), 4) }
    }

    @Test
    fun `composition equals applying both scripts on random texts`() {
        val random = Random(99)
        val edits = RandomEdits(random)
        repeat(5_000) {
            val text = edits.text(25)
            val first = EditScript.of(edits.edits(text.length), text.length)
            val middle = first.applyTo(text)
            val second = EditScript.of(edits.edits(middle.length), middle.length)
            val expected = second.applyTo(middle)

            val composed = first.then(second, text.length)

            assertEquals(expected, composed.applyTo(text), "$first then $second on '$text'")
            assertEquals(text, composed.inverted(text).applyTo(expected))
        }
    }

    @Test
    fun `chains of compositions equal applying every script`() {
        val random = Random(5)
        val edits = RandomEdits(random)
        repeat(300) {
            val initial = edits.text(20)
            var text = initial
            var composed = EditScript.EMPTY
            repeat(20) {
                val next = EditScript.of(edits.edits(text.length, maxEdits = 2), text.length)
                composed = composed.then(next, initial.length)
                text = next.applyTo(text)
                assertEquals(text, composed.applyTo(initial))
            }
        }
    }
}
