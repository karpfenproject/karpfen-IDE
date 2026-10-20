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
