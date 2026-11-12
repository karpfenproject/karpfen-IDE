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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tests the text a user cannot change: it refuses every change except the one that follows the file on disk. */
class ReadOnlyEditableTextTest {

    private val text = EditableText("{ \"x\": 1 }").readOnly()

    @Test
    fun `typing, pasting, cutting and commands change nothing`() {
        val origins = listOf(EditOrigin.Typing, EditOrigin.Paste, EditOrigin.Cut, EditOrigin.Command("duplicate line"), EditOrigin.Programmatic("test"))

        origins.forEach { origin ->
            assertNull(text.apply(listOf(TextEdit.insert(0, "x")), origin), "$origin went through")
            assertNull(text.replaceAll("other", origin), "$origin replaced the text")
        }

        assertEquals("{ \"x\": 1 }", text.snapshot.value.text)
        assertEquals(0, text.snapshot.value.version)
    }

    @Test
    fun `a change from outside goes through, so the text follows its file`() {
        assertNotNull(text.replaceAll("{ \"x\": 2 }", EditOrigin.External))

        assertEquals("{ \"x\": 2 }", text.snapshot.value.text)
        assertEquals(1, text.snapshot.value.version)
    }

    @Test
    fun `there is nothing to undo, also after a change from outside`() {
        text.replaceAll("{ }", EditOrigin.External)

        assertFalse(text.undo.canUndo.value)
        assertFalse(text.undo.canRedo.value)
        assertNull(text.undo.undo())
        assertNull(text.undo.redo())
        assertEquals("{ }", text.snapshot.value.text)
    }
}
