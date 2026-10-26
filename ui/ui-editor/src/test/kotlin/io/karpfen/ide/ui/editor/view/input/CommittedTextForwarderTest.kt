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
package io.karpfen.ide.ui.editor.view.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Committed text leaves the proxy field at once, without undo history, in order. */
@OptIn(ExperimentalFoundationApi::class)
class CommittedTextForwarderTest {

    @Test
    fun `committed text is handed over and the field emptied without history`() {
        val field = TextFieldState()
        val committed = mutableListOf<String>()
        val forwarder = CommittedTextForwarder(field) { committed += it }

        forwarder.flush()
        assertEquals(emptyList(), committed, "nothing to forward")

        field.edit { insert(0, "ä") }
        forwarder.flush()
        field.edit { insert(0, "{}") }
        forwarder.flush()

        assertEquals(listOf("ä", "{}"), committed)
        assertEquals("", field.text.toString())
        assertFalse(field.undoState.canUndo, "the field cannot undo text it handed over")
    }
}
