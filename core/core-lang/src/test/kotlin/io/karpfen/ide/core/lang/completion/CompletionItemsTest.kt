package io.karpfen.ide.core.lang.completion

import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers snippet and name items: insertion text, caret placement and ranking. */
class CompletionItemsTest {

    private val range = TextRange(3, 5)

    @Test
    fun `a snippet places the caret at its marker and inserts the template without it`() {
        val item = Snippet("STATE \"…\" { }", "STATE \"\$0\" { }", detail = "state").item(range)

        assertEquals("STATE \"\" { }", item.insertText)
        assertEquals(7, item.caretOffsetInInsert)
        assertEquals(range, item.replaceRange)
        assertEquals(CompletionKind.SNIPPET, item.kind)
        assertEquals("state", item.detail)
    }

    @Test
    fun `a keyword puts the caret after it`() {
        val item = Snippet.keyword("NOT LOOPING").item(range)

        assertEquals("NOT LOOPING", item.insertText)
        assertNull(item.caretOffsetInInsert)
        assertEquals(CompletionKind.KEYWORD, item.kind)
    }

    @Test
    fun `names are quoted outside strings and ranked by sort key`() {
        val quoted = nameItem("Vector", CompletionKind.TYPE, range, quoted = true, rank = 0)
        val plain = nameItem("Robot", CompletionKind.TYPE, range)

        assertEquals("\"Vector\"", quoted.insertText)
        assertEquals("Robot", plain.insertText)
        assertEquals(listOf(quoted, plain), listOf(plain, quoted).sortedBy { it.sortKey })
        assertEquals(listOf("1:a", "1:b"), listOf(sortKey(1, "B"), sortKey(1, "a")).sorted())
    }
}
