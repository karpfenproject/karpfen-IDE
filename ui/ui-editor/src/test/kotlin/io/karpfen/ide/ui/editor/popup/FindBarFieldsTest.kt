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

/** The find bar's hoisted field state and its synchronization with the published query; the status text and toggles. */
class FindBarFieldsTest {

    @Test
    fun `editing the query returns the query to send, a selection change returns nothing`() {
        val fields = FindBarFields(FindQuery(), focusRequest = 0)

        assertEquals(FindQuery("fo"), fields.editQuery(TextFieldValue("fo", TextRange(2))))
        assertNull(fields.editQuery(TextFieldValue("fo", TextRange(0, 2))), "only the selection changed")
        assertEquals(TextRange(0, 2), fields.queryField.selection)
        assertEquals(FindQuery("fo"), fields.query)
    }

    @Test
    fun `toggles switch one flag and keep the text`() {
        val fields = FindBarFields(FindQuery("x"), focusRequest = 0)

        assertEquals(FindQuery("x", caseSensitive = true), fields.toggle(FindOption.MATCH_CASE))
        assertEquals(FindQuery("x", caseSensitive = true, wholeWord = true), fields.toggle(FindOption.WHOLE_WORD))
        assertEquals(FindQuery("x", caseSensitive = true, wholeWord = true, regex = true), fields.toggle(FindOption.REGEX))
        assertEquals(FindQuery("x", wholeWord = true, regex = true), fields.toggle(FindOption.MATCH_CASE))
        FindOption.entries.forEach { assertEquals(!it.isOn(FindQuery()), it.isOn(it.toggle(FindQuery()))) }
    }

    @Test
    fun `a fresh bar follows the published query and selects it`() {
        val fields = FindBarFields(FindQuery(), focusRequest = 0)

        fields.follow(FindQuery("seed"))

        assertEquals(FindQuery("seed"), fields.query)
        assertEquals(TextFieldValue("seed", TextRange(0, 4)), fields.queryField)
    }

    @Test
    fun `a published query lagging behind the user's typing never reverts the field`() {
        val fields = FindBarFields(FindQuery(), focusRequest = 0)
        fields.editQuery(TextFieldValue("a", TextRange(1)))
        fields.editQuery(TextFieldValue("ab", TextRange(2)))

        fields.follow(FindQuery("a"))

        assertEquals("ab", fields.queryField.text)
        assertEquals(FindQuery("ab"), fields.query)
    }

    @Test
    fun `quick toggles use the bar's own query, not the lagging published one`() {
        val fields = FindBarFields(FindQuery("x"), focusRequest = 0)

        fields.toggle(FindOption.MATCH_CASE)
        fields.follow(FindQuery("x"))
        val second = fields.toggle(FindOption.MATCH_CASE)

        assertEquals(FindQuery("x"), second, "two clicks switch the flag off again")
    }

    @Test
    fun `a new focus request follows the model again, adopts its query and selects it`() {
        val fields = FindBarFields(FindQuery(), focusRequest = 3)
        fields.editQuery(TextFieldValue("old", TextRange(3)))

        assertFalse(fields.takeFocusRequest(3, FindQuery("old")), "request 3 was handled at creation")
        assertTrue(fields.takeFocusRequest(4, FindQuery("old")))
        assertEquals(TextFieldValue("old", TextRange(0, 3)), fields.queryField, "the query is selected")
        assertFalse(fields.takeFocusRequest(4, FindQuery("old")), "handled once")

        fields.follow(FindQuery("seeded"))
        assertEquals(TextFieldValue("seeded", TextRange(0, 6)), fields.queryField, "a seed published after the request arrives")
    }

    @Test
    fun `a focus request adopts a query the model published already`() {
        val fields = FindBarFields(FindQuery(), focusRequest = 0)
        fields.editQuery(TextFieldValue("typed", TextRange(5)))

        assertTrue(fields.takeFocusRequest(1, FindQuery("bar", wholeWord = true)))

        assertEquals(FindQuery("bar", wholeWord = true), fields.query)
        assertEquals(TextFieldValue("bar", TextRange(0, 3)), fields.queryField)
    }

    @Test
    fun `the replacement is kept`() {
        val fields = FindBarFields(FindQuery(), focusRequest = 0)
        fields.replacementField = TextFieldValue("new")
        fields.takeFocusRequest(1, FindQuery("x"))

        assertEquals("new", fields.replacementField.text)
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
