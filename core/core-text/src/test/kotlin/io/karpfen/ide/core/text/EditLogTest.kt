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
import kotlin.test.assertNull

/**
 * Covers [EditLog] and [EditLogWriter]: mapping through the logged changes, eviction beyond the limit, published logs
 * that never change while the writer appends, and capacities for huge limits.
 */
class EditLogTest {

    private fun insertAt(offset: Int, length: Int = 1) = EditScript.of(listOf(TextEdit.insert(offset, "x".repeat(length))), Int.MAX_VALUE)

    @Test
    fun `an empty log maps only its base version`() {
        val log = EditLogWriter(limit = 4, baseVersion = 0).log

        assertEquals(0, log.oldestVersion)
        assertEquals(0, log.newestVersion)
        assertEquals(7, log.mapOffset(7, 0, 0, Bias.RIGHT))
        assertNull(log.mapOffset(7, -1, 0, Bias.RIGHT), "negative versions are older than every history")
        assertFailsWith<IllegalArgumentException> { log.mapOffset(7, 0, 1, Bias.RIGHT) }
    }

    @Test
    fun `offsets are mapped through every change in between`() {
        val writer = EditLogWriter(limit = 10, baseVersion = 0)
        writer.append(insertAt(0))
        writer.append(insertAt(6, length = 2))
        val log = writer.append(insertAt(0))

        assertEquals(3, log.newestVersion)
        assertEquals(9, log.mapOffset(5, 0, 3, Bias.RIGHT))
        assertEquals(7, log.mapOffset(5, 0, 3, Bias.LEFT))
        assertEquals(6, log.mapOffset(6, 1, 2, Bias.LEFT))
        assertEquals(8, log.mapOffset(6, 1, 2, Bias.RIGHT))
        assertFailsWith<IllegalArgumentException> { log.mapOffset(5, 2, 1, Bias.LEFT) }
    }

    @Test
    fun `the oldest changes are evicted beyond the limit`() {
        val writer = EditLogWriter(limit = 3, baseVersion = 0)
        repeat(5) { writer.append(insertAt(0)) }
        val log = writer.log

        assertEquals(2, log.oldestVersion)
        assertEquals(5, log.newestVersion)
        assertEquals(3, log.mapOffset(0, 2, 5, Bias.RIGHT))
        assertNull(log.mapOffset(0, 1, 5, Bias.RIGHT))
        assertNull(log.mapOffset(0, 1, 1, Bias.RIGHT), "an evicted version is not mapped even to itself")
    }

    @Test
    fun `published logs never change while the writer appends, grows and evicts`() {
        for (limit in listOf(1, 2, 3, 16, 17, 100)) {
            val writer = EditLogWriter(limit, baseVersion = 0)
            val views = ArrayList<EditLog>()
            repeat(500) { views += writer.append(insertAt(0, length = 1 + it % 3)) }

            views.forEachIndexed { index, view ->
                val newest = index + 1L
                assertEquals(newest, view.newestVersion, "limit $limit")
                assertEquals(maxOf(0L, newest - limit), view.oldestVersion, "limit $limit")
                val expectedShift = (view.oldestVersion until newest).sumOf { (it % 3 + 1).toInt() }
                assertEquals(expectedShift, view.mapOffset(0, view.oldestVersion, newest, Bias.RIGHT), "limit $limit, view $index")
            }
        }
    }

    @Test
    fun `huge limits do not overflow the capacity`() {
        val writer = EditLogWriter(Int.MAX_VALUE, baseVersion = 0)
        repeat(100) { writer.append(insertAt(0)) }

        assertEquals(100, writer.log.mapOffset(0, 0, 100, Bias.RIGHT))
        assertFailsWith<IllegalArgumentException> { EditLogWriter(0, baseVersion = 0) }
    }
}
