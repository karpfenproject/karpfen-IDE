package io.karpfen.ide.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [LineSeparators]: normalization, detection of the dominant and of mixed separators with its tie-breaking
 * order, and the round trip through [LineSeparators.restore].
 */
class LineSeparatorsTest {

    @Test
    fun `each separator is normalized to newlines and detected as dominant`() {
        assertEquals(NormalizedText("a\nb\n", LineSeparator.LF, mixed = false), LineSeparators.normalize("a\nb\n"))
        assertEquals(NormalizedText("a\nb\n", LineSeparator.CRLF, mixed = false), LineSeparators.normalize("a\r\nb\r\n"))
        assertEquals(NormalizedText("a\nb\n", LineSeparator.CR, mixed = false), LineSeparators.normalize("a\rb\r"))
    }

    @Test
    fun `text without separators is LF and returned unchanged`() {
        val raw = "no separators"

        val normalized = LineSeparators.normalize(raw)

        assertEquals(NormalizedText(raw, LineSeparator.LF, mixed = false), normalized)
        assertTrue(normalized.text === raw, "fast path keeps the instance")
        assertEquals(NormalizedText("", LineSeparator.LF, mixed = false), LineSeparators.normalize(""))
    }

    @Test
    fun `mixed separators are reported and the most frequent one wins`() {
        val mostlyCrlf = LineSeparators.normalize("a\r\nb\r\nc\nd")

        assertEquals("a\nb\nc\nd", mostlyCrlf.text)
        assertEquals(LineSeparator.CRLF, mostlyCrlf.dominant)
        assertTrue(mostlyCrlf.mixed)

        val crThenCrlf = LineSeparators.normalize("a\r\r\nb")
        assertEquals("a\n\nb", crThenCrlf.text, "a CR directly before a CRLF is a separator of its own")
        assertTrue(crThenCrlf.mixed)
    }

    @Test
    fun `ties are broken in the order LF, CRLF, CR`() {
        assertEquals(LineSeparator.LF, LineSeparators.normalize("a\nb\r\nc").dominant)
        assertEquals(LineSeparator.CRLF, LineSeparators.normalize("a\rb\r\nc").dominant)
        assertEquals(LineSeparator.LF, LineSeparators.normalize("a\rb\nc").dominant)
    }

    @Test
    fun `restore replaces every newline by the separator`() {
        assertEquals("a\r\nb\r\n", LineSeparators.restore("a\nb\n", LineSeparator.CRLF))
        assertEquals("a\rb", LineSeparators.restore("a\nb", LineSeparator.CR))
        assertEquals("a\nb", LineSeparators.restore("a\nb", LineSeparator.LF))
    }

    @Test
    fun `normalize then restore round-trips texts with one kind of separator`() {
        val random = Random(42)
        repeat(500) {
            val separator = LineSeparator.entries[random.nextInt(LineSeparator.entries.size)]
            val raw = buildString {
                repeat(random.nextInt(40)) { append(if (random.nextInt(5) == 0) separator.chars else "x") }
            }

            val normalized = LineSeparators.normalize(raw)

            assertFalse(normalized.mixed, raw)
            assertFalse('\r' in normalized.text)
            assertEquals(raw, LineSeparators.restore(normalized.text, normalized.dominant))
        }
    }

    @Test
    fun `mixed detection and normalization agree with counting separators on random texts`() {
        val random = Random(7)
        repeat(500) {
            val raw = buildString { repeat(random.nextInt(30)) { append("ab\r\n"[random.nextInt(4)]) } }

            val normalized = LineSeparators.normalize(raw)

            val crlf = Regex("\r\n").findAll(raw).count()
            val cr = raw.count { it == '\r' } - crlf
            val lf = raw.count { it == '\n' } - crlf
            assertEquals(listOf(lf, crlf, cr).count { it > 0 } > 1, normalized.mixed, raw)
            assertEquals(raw.replace("\r\n", "\n").replace('\r', '\n'), normalized.text)
        }
    }
}
