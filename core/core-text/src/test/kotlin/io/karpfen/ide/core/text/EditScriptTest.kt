package io.karpfen.ide.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditScriptTest {

    @Test
    fun `edits are sorted, no-ops dropped and same-offset insertions kept in list order`() {
        val script = EditScript.of(
            listOf(TextEdit.insert(5, "b"), TextEdit.delete(1, 2), TextEdit.insert(3, ""), TextEdit.insert(5, "c")),
            textLength = 6,
        )

        assertEquals(listOf(TextEdit.delete(1, 2), TextEdit.insert(5, "b"), TextEdit.insert(5, "c")), script.edits)
        assertEquals("acdebcf", script.applyTo("abcdef"))
        assertTrue(EditScript.of(listOf(TextEdit.insert(0, "")), 0).isEmpty)
    }

    @Test
    fun `an insertion at the start of a replaced range goes before the replacement`() {
        val script = EditScript.of(listOf(TextEdit.replace(TextRange(1, 3), "XY"), TextEdit.insert(1, "i")), 4)

        assertEquals("aiXYd", script.applyTo("abcd"))
        assertEquals(listOf(TextEdit.insert(1, "i"), TextEdit.replace(TextRange(1, 3), "XY")), script.edits)
    }

    @Test
    fun `adjacent edits are allowed, overlapping ones and insertions inside replaced ranges are not`() {
        assertEquals("XYc", EditScript.of(listOf(TextEdit.replace(TextRange(0, 1), "X"), TextEdit.replace(TextRange(1, 2), "Y")), 3).applyTo("abc"))
        assertEquals("abxc", EditScript.of(listOf(TextEdit.delete(2, 3), TextEdit.insert(3, "xc")), 3).applyTo("abc"))

        assertFailsWith<IllegalArgumentException> { EditScript.of(listOf(TextEdit.delete(0, 2), TextEdit.delete(1, 3)), 3) }
        assertFailsWith<IllegalArgumentException> { EditScript.of(listOf(TextEdit.delete(0, 3), TextEdit.insert(1, "x")), 3) }
        assertFailsWith<IllegalArgumentException> { EditScript.of(listOf(TextEdit.delete(0, 4)), 3) }
        assertFailsWith<IllegalArgumentException> { EditScript.of(listOf(TextEdit.insert(4, "x")), 3) }
    }

    @Test
    fun `the inverse restores the text`() {
        val before = "hello world"
        val script = EditScript.of(
            listOf(TextEdit.insert(0, ">"), TextEdit.replace(TextRange(6, 11), "there"), TextEdit.delete(5, 6), TextEdit.insert(0, ">")),
            before.length,
        )

        val after = script.applyTo(before)

        assertEquals(">>hellothere", after)
        assertEquals(before, script.inverted(before).applyTo(after))
    }

    @Test
    fun `offsets follow the bias at insertions and inside replacements`() {
        val insertion = EditScript.of(listOf(TextEdit.insert(2, "xyz")), 5)
        assertEquals(2, insertion.mapOffset(2, Bias.LEFT))
        assertEquals(5, insertion.mapOffset(2, Bias.RIGHT))
        assertEquals(1, insertion.mapOffset(1, Bias.RIGHT), "before every edit")
        assertEquals(6, insertion.mapOffset(3, Bias.LEFT), "after every edit")

        val replacement = EditScript.of(listOf(TextEdit.replace(TextRange(2, 6), "ab")), 8)
        for (offset in 2..6) {
            assertEquals(2, replacement.mapOffset(offset, Bias.LEFT), "LEFT inside or on the bounds of a replacement")
            assertEquals(4, replacement.mapOffset(offset, Bias.RIGHT), "RIGHT inside or on the bounds of a replacement")
        }
        assertEquals(5, replacement.mapOffset(7, Bias.LEFT))
        assertEquals(20, replacement.mapOffset(22, Bias.RIGHT), "offsets past the text shift like any later offset")
    }

    @Test
    fun `adjacent deletions and same-offset insertions are passed as a whole`() {
        val script = EditScript.of(
            listOf(TextEdit.delete(2, 4), TextEdit.delete(4, 6), TextEdit.insert(6, "a"), TextEdit.insert(6, "b")),
            8,
        )

        assertEquals(2, script.mapOffset(5, Bias.LEFT))
        assertEquals(4, script.mapOffset(3, Bias.RIGHT), "behind both insertions at the end of the deleted run")
        assertEquals(2, script.mapOffset(6, Bias.LEFT))
        assertEquals(4, script.mapOffset(6, Bias.RIGHT))
    }

    @Test
    fun `scripts of many edits, with runs of adjacent replacements, map like the reference`() {
        val random = Random(7)
        val edits = RandomEdits(random)
        repeat(300) {
            val text = edits.text(200)
            val split = text.length / 2
            // Many random edits in the first half, a run of adjacent replacements and insertions in the second.
            val list = edits.edits(split, maxEdits = 60) + adjacentRun(split + 1, text.length)
            val script = EditScript.of(list, text.length)
            val reference = ReferenceText(text)
            val trackers = (0..text.length).flatMap { offset -> Bias.entries.map { reference.track(0, offset, it) } }

            reference.apply(list)

            for (tracker in trackers) {
                assertEquals(tracker.offset, script.mapOffset(tracker.original, tracker.bias), "${tracker.bias} ${tracker.original} through ${script.edits}")
            }
        }
    }

    /** Adjacent replacements with insertions at their shared bounds from [start] on, if a text of [length] has room. */
    private fun adjacentRun(start: Int, length: Int): List<TextEdit> =
        if (length - start < 10) {
            emptyList()
        } else {
            listOf(
                TextEdit.replace(TextRange(start, start + 3), "ab"),
                TextEdit.insert(start + 3, "i"),
                TextEdit.delete(start + 3, start + 5),
                TextEdit.insert(start + 5, "j"),
                TextEdit.replace(TextRange(start + 5, start + 9), "xyz"),
            )
        }

    @Test
    fun `random scripts apply, invert and map like the reference`() {
        val random = Random(1)
        val edits = RandomEdits(random)
        repeat(3_000) {
            val text = edits.text(30)
            val list = edits.edits(text.length)
            val script = EditScript.of(list, text.length)
            val reference = ReferenceText(text)
            val trackers = (0..text.length).flatMap { offset -> Bias.entries.map { reference.track(0, offset, it) } }

            reference.apply(list)

            val after = script.applyTo(text)
            assertEquals(reference.text, after, "apply $list to '$text'")
            assertEquals(text, script.inverted(text).applyTo(after), "inverse of $list")
            for (tracker in trackers) {
                assertEquals(tracker.offset, script.mapOffset(tracker.original, tracker.bias), "${tracker.bias} ${tracker.original} through $list in '$text'")
            }
        }
    }
}
