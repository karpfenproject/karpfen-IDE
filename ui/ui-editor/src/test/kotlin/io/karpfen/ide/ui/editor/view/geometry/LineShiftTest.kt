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
package io.karpfen.ide.ui.editor.view.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The display-only shift of lines: rows of lines, lines of rows and the identity without a shift. */
class LineShiftTest {

    @Test
    fun `lines after the shifted line move down by the added rows`() {
        val shift = LineShift(line = 2, added = 3)

        assertEquals(listOf(0, 1, 2, 6, 7), (0..4).map(shift::rowOf))
        assertEquals(5, shift.lastRowOf(2), "the shifted line spans its added rows")
        assertEquals(6, shift.lastRowOf(3))
        assertEquals(10, shift.rowCount(7))
    }

    @Test
    fun `every row shows one line and the added rows show the shifted line`() {
        val shift = LineShift(line = 2, added = 3)

        assertEquals(listOf(0, 1, 2, 2, 2, 2, 3, 4), (0..7).map(shift::lineAt))
        assertEquals(-1, shift.lineAt(-1), "rows above the text pass unchanged")
        for (line in 0..9) assertEquals(line, shift.lineAt(shift.rowOf(line)), "line $line")
    }

    @Test
    fun `no shift is the identity`() {
        for (n in -1..5) {
            assertEquals(n, LineShift.NONE.rowOf(n))
            assertEquals(n, LineShift.NONE.lineAt(n))
            assertEquals(n, LineShift.NONE.lastRowOf(n))
        }
        assertEquals(4, LineShift.NONE.rowCount(4))
        assertEquals(listOf(0, 1, 2), (0..2).map(LineShift(5, 0)::rowOf), "a shift without added rows moves nothing")
    }

    @Test
    fun `a shift cannot remove rows`() {
        assertFailsWith<IllegalArgumentException> { LineShift(1, -1) }
    }
}
