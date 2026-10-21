package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.model.find.FindResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The find bar's hoisted field state (selection, composition) over the model's query and replacement; the status and toggles. */
class FindBarFieldsTest {

    @Test
    fun `the query field shows the model's text and keeps the user's selection while it does`() {
        val fields = FindBarFields(focusRequest = 0)

        assertEquals("fo", fields.editQuery(TextFieldValue("fo", TextRange(2)), shownText = ""), "a new text to send")
        assertEquals(TextFieldValue("fo", TextRange(2)), fields.queryField("fo"))
        assertNull(fields.editQuery(TextFieldValue("fo", TextRange(0, 2)), shownText = "fo"), "only the selection changed")
        assertEquals(TextRange(0, 2), fields.queryField("fo").selection)
    }

    @Test
    fun `a text the model got elsewhere is shown selected`() {
        val fields = FindBarFields(focusRequest = 0)
        fields.editQuery(TextFieldValue("typed", TextRange(5)), shownText = "")

        assertEquals(TextFieldValue("seed", TextRange(0, 4)), fields.queryField("seed"), "e.g. seeded from the selection")
    }

    @Test
    fun `toggles switch one flag and keep the text`() {
        assertEquals(FindQuery("x", caseSensitive = true), FindOption.MATCH_CASE.toggle(FindQuery("x")))
        assertEquals(FindQuery("x", wholeWord = true), FindOption.WHOLE_WORD.toggle(FindQuery("x")))
        assertEquals(FindQuery("x"), FindOption.REGEX.toggle(FindQuery("x", regex = true)))
        FindOption.entries.forEach { assertEquals(!it.isOn(FindQuery()), it.isOn(it.toggle(FindQuery()))) }
    }

    @Test
    fun `a new focus request selects the model's query once`() {
        val fields = FindBarFields(focusRequest = 3)
        fields.editQuery(TextFieldValue("old", TextRange(3)), shownText = "")

        assertFalse(fields.takeFocusRequest(3, "old"), "request 3 was handled at creation")
        assertTrue(fields.takeFocusRequest(4, "old"))
        assertEquals(TextFieldValue("old", TextRange(0, 3)), fields.queryField("old"), "the query is selected")
        assertFalse(fields.takeFocusRequest(4, "old"), "handled once")
        assertTrue(fields.takeFocusRequest(5, "bar"))
        assertEquals(TextFieldValue("bar", TextRange(0, 3)), fields.queryField("bar"))
    }

    @Test
    fun `the replacement field shows the model's replacement and keeps the user's selection while it does`() {
        val fields = FindBarFields(focusRequest = 0)

        assertEquals("new", fields.editReplacement(TextFieldValue("new", TextRange(3)), shownText = ""), "a new text to send")
        fields.takeFocusRequest(1, "x")
        assertEquals(TextFieldValue("new", TextRange(3)), fields.replacementField("new"), "focusing the query leaves it")
        assertNull(fields.editReplacement(TextFieldValue("new", TextRange(0, 1)), shownText = "new"), "only the selection changed")
        assertEquals(TextFieldValue("other", TextRange(0, 5)), fields.replacementField("other"), "a text the model got elsewhere")
    }

    @Test
    fun `the status is the n of m counter, the regex error, or empty`() {
        val matches = listOf(io.karpfen.ide.core.text.TextRange(0, 1), io.karpfen.ide.core.text.TextRange(2, 3))
        assertEquals(FindStatus("2/2", isError = false), FindStatus.of(FindResult(FindQuery("a"), 1, matches, 1, null)))
        assertEquals(FindStatus("0/0", isError = true), FindStatus.of(FindResult(FindQuery("a"), 1, emptyList(), null, null)))
        assertEquals(FindStatus("", isError = false), FindStatus.of(FindResult(FindQuery(""), 1, emptyList(), null, null)))
        assertEquals(
            FindStatus("Unclosed group near index 1", isError = true),
            FindStatus.of(FindResult(FindQuery("(", regex = true), 1, emptyList(), null, "Unclosed group near index 1")),
        )
    }
}
