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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract test of the core-text value objects ([TextRange], [TextPosition], [TextEdit], [CaretMemento], [TextChange],
 * [TextSnapshot] and its lineage), independent of the editable text implementation.
 */
class TextValuesContractTest {

    @Test
    fun `text ranges are half-open and validated`() {
        val range = TextRange(2, 5)

        assertEquals(3, range.length)
        assertTrue(2 in range)
        assertFalse(5 in range)
        assertTrue(range.containsInclusive(5))
        assertTrue(TextRange(3, 4) in range)
        assertTrue(range.overlaps(TextRange(4, 9)))
        assertFalse(range.overlaps(TextRange(5, 9)))
        assertFalse(TextRange.empty(3).overlaps(range))
        assertEquals(TextRange(4, 7), range.shiftedBy(2))
        assertEquals(TextRange(1, 5), range.union(TextRange(1, 2)))
        assertFailsWith<IllegalArgumentException> { TextRange(-1, 0) }
        assertFailsWith<IllegalArgumentException> { TextRange(3, 2) }
    }

    @Test
    fun `edits and positions describe themselves`() {
        assertTrue(TextEdit.insert(3, "x").isInsertion)
        assertTrue(TextEdit.delete(1, 3).isDeletion)
        assertTrue(TextEdit.insert(0, "").isNoOp)
        assertEquals(-1, TextEdit.replace(TextRange(0, 3), "ab").lengthDelta)
        assertTrue(TextPosition(1, 0) > TextPosition(0, 99))
        assertFailsWith<IllegalArgumentException> { TextPosition(0, -1) }
    }

    @Test
    fun `changes advance the version by one and carry edits`() {
        val before = TextSnapshot("ab", 3)
        val after = TextSnapshot("axb", 4)
        val change = TextChange(before, after, listOf(TextEdit.insert(1, "x")), EditOrigin.Typing)

        assertEquals(4, change.version)
        assertFailsWith<IllegalArgumentException> { TextChange(before, TextSnapshot("ab", 5), change.edits, EditOrigin.Typing) }
        assertFailsWith<IllegalArgumentException> { TextChange(before, after, emptyList(), EditOrigin.Typing) }
        assertEquals(TextRange(2, 5), CaretMemento(5, 2).selection)
        assertEquals(EditOrigin.Command("duplicate"), EditOrigin.Command("duplicate"))
    }

    @Test
    fun `snapshots expose text without computing lines`() {
        val snapshot = TextSnapshot("type \"A\"", 0)

        assertEquals(8, snapshot.length)
        assertEquals('t', snapshot[0])
        assertEquals("\"A\"", snapshot.substring(TextRange(5, 8)))
        assertFailsWith<IllegalArgumentException> { TextSnapshot("", -1) }
    }

    @Test
    fun `snapshots created on their own are lineages of their own`() {
        val lineage = TextLineage()

        assertTrue(TextSnapshot("a", 0, lineage).lineage === TextSnapshot("ab", 1, lineage).lineage)
        assertFalse(TextSnapshot("a", 0).lineage == TextSnapshot("a", 0).lineage, "equal versions of unrelated texts")
    }
}
