package io.karpfen.ide.core.text

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [EditableText] by example: versions, atomic and rejected edits, minimal whole-text replacement, lineages,
 * offset mapping and its history limit, and the order in which changes are emitted.
 */
class EditableTextTest {

    @Test
    fun `a new text starts at version 0 with its initial text`() {
        val text = EditableText("state A")

        assertEquals("state A", text.snapshot.value.text)
        assertEquals(0, text.snapshot.value.version)
        assertSame(text.lineage, text.snapshot.value.lineage)
        assertFalse(text.undo.canUndo.value)
        assertTrue(text.undo.isAtSavePoint.value)
    }

    @Test
    fun `apply produces exactly one new version for several edits`() {
        val text = EditableText("abcdef")

        val change = assertNotNull(
            text.apply(listOf(TextEdit.insert(6, "!"), TextEdit.replace(TextRange(0, 1), "A"), TextEdit.delete(2, 4)), EditOrigin.Paste),
        )

        assertEquals("Abef!", text.snapshot.value.text)
        assertEquals(1, change.version)
        assertSame(change.after, text.snapshot.value)
        assertEquals("abcdef", change.before.text)
        assertEquals(EditOrigin.Paste, change.origin)
        assertEquals(listOf(TextEdit.replace(TextRange(0, 1), "A"), TextEdit.delete(2, 4), TextEdit.insert(6, "!")), change.edits, "sorted")
    }

    @Test
    fun `two insertions at one offset are applied in list order`() {
        val text = EditableText("()")

        text.apply(listOf(TextEdit.insert(1, "a"), TextEdit.insert(1, "b")), EditOrigin.Command("test"))

        assertEquals("(ab)", text.snapshot.value.text)
    }

    @Test
    fun `no-op edits produce no version and no change`() {
        val text = EditableText("abc")

        assertNull(text.apply(emptyList(), EditOrigin.Typing))
        assertNull(text.apply(listOf(TextEdit.insert(1, ""), TextEdit.delete(2, 2)), EditOrigin.Typing))
        assertNull(text.replaceAll("abc", EditOrigin.External))
        assertEquals(0, text.snapshot.value.version)
        assertFalse(text.undo.canUndo.value)
    }

    @Test
    fun `invalid edits are rejected atomically`() {
        val text = EditableText("abc")

        assertFailsWith<IllegalArgumentException> {
            text.apply(listOf(TextEdit.insert(0, "x"), TextEdit.delete(1, 3), TextEdit.delete(2, 3)), EditOrigin.Typing)
        }
        assertFailsWith<IllegalArgumentException> { text.apply(listOf(TextEdit.delete(0, 4)), EditOrigin.Typing) }

        assertEquals("abc", text.snapshot.value.text)
        assertEquals(0, text.snapshot.value.version)
        assertFalse(text.undo.canUndo.value)
    }

    @Test
    fun `replaceAll is the minimal edit keeping common prefix and suffix`() {
        val text = EditableText("state Idle {\n}\n")

        val change = assertNotNull(text.replaceAll("state Busy {\n}\n", EditOrigin.External))

        assertEquals(listOf(TextEdit.replace(TextRange(6, 10), "Busy")), change.edits)
        assertEquals("state Busy {\n}\n", text.snapshot.value.text)
        assertEquals(EditOrigin.External, change.origin)
        assertEquals(13, text.mapOffset(13, 0), "offsets outside the changed region map exactly")
    }

    @Test
    fun `minimal replacements are correct for overlapping prefix and suffix`() {
        assertEquals(TextEdit.insert(2, "a"), minimalReplacement("aa", "aaa"))
        assertEquals(TextEdit.delete(2, 3), minimalReplacement("aaa", "aa"))
        assertEquals(TextEdit.replace(TextRange(0, 3), "xyz"), minimalReplacement("abc", "xyz"))
        assertEquals(TextEdit.insert(0, "abc"), minimalReplacement("", "abc"))
        assertEquals(TextEdit.replace(TextRange(1, 2), "XYZ"), minimalReplacement("abc", "aXYZc"))
        assertNull(minimalReplacement("same", "same"))
    }

    @Test
    fun `all snapshots of one text share its lineage and two texts have different ones`() {
        val first = EditableText("a")
        val second = EditableText("a")
        val snapshots = mutableListOf(first.snapshot.value)

        snapshots += assertNotNull(first.apply(listOf(TextEdit.insert(1, "b")), EditOrigin.Typing)).after
        snapshots += assertNotNull(first.undo.undo()).change.after
        snapshots += assertNotNull(first.replaceAll("xyz", EditOrigin.External)).after

        assertTrue(snapshots.all { it.lineage === first.lineage })
        assertFalse(first.lineage === second.lineage)
        assertFalse(first.lineage === second.snapshot.value.lineage)
    }

    @Test
    fun `mapOffset validates its arguments and maps within the current version`() {
        val text = EditableText("0123456789")
        text.apply(listOf(TextEdit.insert(2, "ab")), EditOrigin.Typing)

        assertEquals(7, text.mapOffset(5, 0))
        assertEquals(2, text.mapOffset(2, 0, bias = Bias.LEFT))
        assertEquals(4, text.mapOffset(2, 0, bias = Bias.RIGHT))
        assertEquals(5, text.mapOffset(5, 1, 1))
        assertNull(text.mapOffset(5, -1), "negative versions are older than the history")
        assertFailsWith<IllegalArgumentException> { text.mapOffset(-1, 0) }
        assertFailsWith<IllegalArgumentException> { text.mapOffset(0, 1, 0) }
        assertFailsWith<IllegalArgumentException> { text.mapOffset(0, 0, 2) }
    }

    @Test
    fun `mapOffset returns null once the history is evicted`() {
        val text = EditableText("", historyLimit = 3)
        repeat(5) { text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Programmatic("fill")) }

        assertEquals(3, text.mapOffset(0, 2))
        assertNull(text.mapOffset(0, 1))
        assertNull(text.mapRange(TextRange(0, 1), 1, text.lineage))
    }

    @Test
    fun `the default history keeps at least 1000 versions`() {
        val text = EditableText()
        repeat(1_500) { text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Programmatic("fill")) }

        assertEquals(1_000, text.mapOffset(0, 500))
        assertNull(text.mapOffset(0, 499))
        assertFailsWith<IllegalArgumentException> { EditableText(historyLimit = 0) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `every change is emitted exactly once and in order, undo and redo included`() = runTest {
        val text = EditableText("")
        val emitted = mutableListOf<TextChange>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { text.changes.toList(emitted) }

        val applied = listOfNotNull(
            text.apply(listOf(TextEdit.insert(0, "a")), EditOrigin.Typing),
            text.apply(emptyList(), EditOrigin.Typing),
            text.replaceAll("abc", EditOrigin.Paste),
            text.undo.undo()?.change,
            text.undo.redo()?.change,
        )
        collector.cancel()

        assertEquals(applied, emitted)
        assertEquals(listOf(1L, 2L, 3L, 4L), emitted.map { it.version })
        assertEquals(listOf(EditOrigin.Typing, EditOrigin.Paste, EditOrigin.Undo, EditOrigin.Redo), emitted.map { it.origin })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `changes applied by an inline collector are published after the change that triggered them`() = runTest {
        val text = EditableText("")
        val versions = mutableListOf<Long>()
        val snapshots = mutableListOf<Long>()
        val reactor = launch(UnconfinedTestDispatcher(testScheduler)) {
            text.changes.collect { change ->
                versions += change.version
                if (change.origin == EditOrigin.Typing) text.apply(listOf(TextEdit.insert(change.after.length, ")")), EditOrigin.Command("close"))
            }
        }
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) { text.snapshot.collect { snapshots += it.version } }

        text.apply(listOf(TextEdit.insert(0, "(")), EditOrigin.Typing)
        reactor.cancel()
        observer.cancel()

        assertEquals("()", text.snapshot.value.text)
        assertEquals(listOf(1L, 2L), versions)
        assertEquals(snapshots.sorted(), snapshots, "snapshot versions never go back")
        assertEquals(2L, snapshots.last())
    }
}
