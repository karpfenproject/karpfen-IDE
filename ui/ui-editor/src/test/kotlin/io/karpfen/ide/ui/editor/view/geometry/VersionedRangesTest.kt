package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Finding the ranges of an older version that intersect a window of the current text, mapped with `mapRange`. */
class VersionedRangesTest {

    @Test
    fun `with the identity only the intersecting ranges are returned`() {
        val ranges = listOf(TextRange(0, 2), TextRange(4, 6), TextRange(8, 10), TextRange(12, 14))
        val index = VersionedRanges(ranges, { it }, { it })

        assertEquals(listOf(TextRange(4, 6), TextRange(8, 10)), index.intersecting(TextRange(5, 9)).map { it.range })
        assertEquals(emptyList(), index.intersecting(TextRange(2, 4)))
        assertEquals(ranges, index.intersecting(TextRange(0, 20)).map { it.item })
    }

    @Test
    fun `ranges of an older version are mapped, collapsed ones dropped`() {
        val text = EditableText("aaaa bbbb cccc dddd")
        val old = text.snapshot.value
        val ranges = listOf(TextRange(0, 4), TextRange(5, 9), TextRange(10, 14), TextRange(15, 19))
        text.apply(listOf(TextEdit.insert(0, "XX"), TextEdit(TextRange(5, 9), "")), EditOrigin.Typing)

        val index = VersionedRanges(ranges, { it }, { text.mapRange(it, old) })

        assertEquals("XXaaaa  cccc dddd", text.snapshot.value.text)
        assertEquals(listOf(TextRange(2, 6), TextRange(8, 12), TextRange(13, 17)), index.intersecting(TextRange(0, 30)).map { it.range })
        assertEquals(listOf(TextRange(13, 17)), index.intersecting(TextRange(14, 15)).map { it.range })
    }

    @Test
    fun `ranges that do not map at all are not drawn`() {
        val index = VersionedRanges(listOf(TextRange(0, 1)), { it }, { null })

        assertEquals(emptyList(), index.intersecting(TextRange(0, 5)))
    }

    @Test
    fun `binary search agrees with a linear scan on random edits`() {
        val random = Random(42)
        repeat(50) {
            val text = EditableText("x".repeat(400))
            val old = text.snapshot.value
            val ranges = (0 until 400 step 7).map { TextRange(it, it + random.nextInt(1, 6)) }
            repeat(5) {
                val length = text.snapshot.value.length
                val start = random.nextInt(0, length)
                val end = (start + random.nextInt(0, 20)).coerceAtMost(length)
                text.apply(listOf(TextEdit(TextRange(start, end), "y".repeat(random.nextInt(0, 20)))), EditOrigin.Typing)
            }
            val map = { range: TextRange -> text.mapRange(range, old) }
            val windowStart = random.nextInt(0, text.snapshot.value.length)
            val window = TextRange(windowStart, (windowStart + random.nextInt(1, 80)).coerceAtMost(text.snapshot.value.length))

            val expected = ranges.mapNotNull(map).filter { !it.isEmpty && it.end > window.start && it.start < window.end }
            assertEquals(expected, VersionedRanges(ranges, { it }, map).intersecting(window).map { it.range })
        }
    }
}
