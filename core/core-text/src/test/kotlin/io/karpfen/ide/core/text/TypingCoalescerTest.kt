package io.karpfen.ide.core.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Covers the rules of [TypingCoalescer]: which changes are keystrokes, and when a keystroke continues the previous run
 * (contiguity, the time window, newlines and whitespace after a word character).
 */
class TypingCoalescerTest {

    private val time = TestTimeSource()
    private val coalescer = TypingCoalescer(time)

    private fun change(before: String, origin: EditOrigin, vararg edits: TextEdit): TextChange {
        val snapshot = TextSnapshot(before, 0)
        val script = EditScript.of(edits.toList(), before.length)
        return TextChange(snapshot, TextSnapshot(script.applyTo(before), 1, snapshot.lineage), script.edits, origin)
    }

    private fun typed(before: String, offset: Int, char: String) = coalescer.keystrokeOf(change(before, EditOrigin.Typing, TextEdit.insert(offset, char)))

    @Test
    fun `only single typed or deleted characters are keystrokes`() {
        assertIs<Keystroke.Insertion>(typed("ab", 1, "x"))
        assertIs<Keystroke.Insertion>(typed("ab", 1, "😀"), "a surrogate pair is one character")
        assertIs<Keystroke.Deletion>(coalescer.keystrokeOf(change("ab", EditOrigin.Typing, TextEdit.delete(0, 1))))
        assertIs<Keystroke.Deletion>(coalescer.keystrokeOf(change("😀", EditOrigin.Typing, TextEdit.delete(0, 2))))

        assertNull(typed("ab", 1, "xy"), "two characters")
        assertNull(coalescer.keystrokeOf(change("abc", EditOrigin.Typing, TextEdit.delete(0, 2))), "two characters deleted")
        assertNull(coalescer.keystrokeOf(change("abc", EditOrigin.Typing, TextEdit.replace(TextRange(0, 1), "x"))), "replacement")
        assertNull(coalescer.keystrokeOf(change("abc", EditOrigin.Typing, TextEdit.insert(0, "x"), TextEdit.insert(2, "x"))), "two carets")
        assertNull(coalescer.keystrokeOf(change("ab", EditOrigin.Paste, TextEdit.insert(1, "x"))), "pasted")
        assertNull(coalescer.keystrokeOf(change("ab", EditOrigin.Programmatic("p"), TextEdit.insert(1, "x"))), "programmatic")
    }

    @Test
    fun `contiguous insertions and deletions continue a run within the window`() {
        val first = typed("", 0, "a")!!
        time += 1000.milliseconds
        val second = typed("a", 1, "b")!!
        assertTrue(coalescer.continues(first, second))
        assertFalse(coalescer.continues(first, typed("a", 0, "b")!!), "not contiguous")

        val backspace = coalescer.keystrokeOf(change("abc", EditOrigin.Typing, TextEdit.delete(2, 3)))!!
        assertTrue(coalescer.continues(backspace, coalescer.keystrokeOf(change("ab", EditOrigin.Typing, TextEdit.delete(1, 2)))!!))
        val forward = coalescer.keystrokeOf(change("abc", EditOrigin.Typing, TextEdit.delete(1, 2)))!!
        assertTrue(coalescer.continues(forward, coalescer.keystrokeOf(change("ac", EditOrigin.Typing, TextEdit.delete(1, 2)))!!))
        assertFalse(coalescer.continues(forward, typed("ac", 1, "x")!!), "a deletion run does not continue with typing")
    }

    @Test
    fun `a pause longer than the window ends the run`() {
        val first = typed("", 0, "a")!!
        time += 1001.milliseconds

        assertFalse(coalescer.continues(first, typed("a", 1, "b")!!))
    }

    @Test
    fun `a newline or whitespace after a word character ends the run`() {
        assertFalse(coalescer.continues(typed("", 0, "a")!!, typed("a", 1, "\n")!!))
        assertFalse(coalescer.continues(typed("", 0, ";")!!, typed(";", 1, "\n")!!))
        assertFalse(coalescer.continues(typed("", 0, "_")!!, typed("_", 1, " ")!!))
        assertFalse(coalescer.continues(typed("", 0, "ä")!!, typed("ä", 1, "\t")!!))
        assertTrue(coalescer.continues(typed("", 0, ";")!!, typed(";", 1, " ")!!))
        assertTrue(coalescer.continues(typed("", 0, " ")!!, typed(" ", 1, " ")!!))
        assertTrue(coalescer.continues(typed("", 0, "\n")!!, typed("\n", 1, "a")!!))
    }
}
