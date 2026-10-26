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

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract test of [EditableText.mapRange], the one range-mapping rule, which the interface implements itself. It runs
 * on a fake text whose [EditableText.mapOffset] knows exactly one replacement from version 0 to version 1, so it
 * checks the rule independently of [DefaultEditableText].
 */
class RangeMappingContractTest {

    /** Version 0 → 1 replaced [replaced] by [insertedLength] characters; versions before [oldestVersion] are evicted. */
    private class OneEditText(
        private val replaced: TextRange,
        private val insertedLength: Int,
        private val oldestVersion: Long = 0,
    ) : EditableText {
        private val sharedLineage = TextLineage()
        override val snapshot: StateFlow<TextSnapshot> = MutableStateFlow(TextSnapshot("", 1, sharedLineage))
        override val changes: SharedFlow<TextChange> = MutableSharedFlow()
        override val undo: UndoManager get() = error("not used")

        override fun apply(
            edits: List<TextEdit>,
            origin: EditOrigin,
            caretsBefore: List<CaretMemento>?,
            caretsAfter: List<CaretMemento>?,
        ): TextChange? = error("not used")

        override fun replaceAll(newText: String, origin: EditOrigin): TextChange? = error("not used")

        override fun mapOffset(offset: Int, fromVersion: Long, toVersion: Long, bias: Bias): Int? {
            require(offset >= 0 && fromVersion <= toVersion && toVersion <= 1)
            if (fromVersion < oldestVersion) return null
            if (fromVersion == toVersion) return offset
            val delta = insertedLength - replaced.length
            return when {
                offset < replaced.start -> offset
                offset > replaced.end -> offset + delta
                replaced.isEmpty -> if (bias == Bias.LEFT) offset else offset + insertedLength
                offset == replaced.start && bias == Bias.LEFT -> offset
                offset == replaced.end && bias == Bias.RIGHT -> offset + delta
                else -> if (bias == Bias.LEFT) replaced.start else replaced.start + insertedLength
            }
        }
    }

    @Test
    fun `a range moves with edits before it and keeps text inserted at its bounds outside`() {
        val typedAt5 = OneEditText(TextRange.empty(5), insertedLength = 1)

        assertEquals(TextRange(6, 11), typedAt5.mapRange(TextRange(5, 10), 0, typedAt5.lineage))
        assertEquals(TextRange(1, 5), typedAt5.mapRange(TextRange(1, 5), 0, typedAt5.lineage), "insertion at the end stays outside")
        assertEquals(TextRange(3, 4), OneEditText(TextRange(0, 2), 1).let { it.mapRange(TextRange(4, 5), 0, it.lineage) })
    }

    @Test
    fun `empty ranges stay empty and follow typed text`() {
        val typedAt7 = OneEditText(TextRange.empty(7), insertedLength = 1)

        assertEquals(TextRange.empty(8), typedAt7.mapRange(TextRange.empty(7), 0, typedAt7.lineage))
        assertEquals(TextRange.empty(3), typedAt7.mapRange(TextRange.empty(3), 0, typedAt7.lineage))
    }

    @Test
    fun `a range inside a replaced region collapses instead of inverting`() {
        val replaced = OneEditText(TextRange(3, 12), insertedLength = 1)

        assertEquals(TextRange.empty(3), replaced.mapRange(TextRange(5, 10), 0, replaced.lineage))
        assertEquals(TextRange(4, 7), replaced.mapRange(TextRange(10, 15), 0, replaced.lineage), "partly replaced ranges shrink")
    }

    @Test
    fun `ranges of other lineages, newer versions and evicted history are not mapped`() {
        val text = OneEditText(TextRange.empty(0), insertedLength = 1, oldestVersion = 1)
        val range = TextRange(2, 4)

        assertNull(text.mapRange(range, 0, TextLineage()), "another lineage")
        assertNull(text.mapRange(range, 0, text.lineage), "evicted")
        assertNull(text.mapRange(range, 2, text.lineage), "newer than the current version")
        assertNull(text.mapRange(range, 1, text.lineage, toVersion = 0), "newer than the target version")
        assertNull(text.mapRange(range, -1, text.lineage), "negative version")
        assertEquals(range, text.mapRange(range, TextSnapshot("ab cd", 1, text.lineage)), "same version: unchanged")
    }
}
