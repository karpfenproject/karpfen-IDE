package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextRange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The search rules: case, whole word, regex, invalid regex, replacement expansion. */
class FindMatcherTest {

    private val matcher = FindMatcher()

    private fun ranges(text: String, query: FindQuery): List<String> =
        matcher.find(text, query).matches.map { text.substring(it.start, it.end) + "@" + it.start }

    @Test
    fun `plain search is case-insensitive by default and literal`() {
        assertEquals(listOf("Foo@0", "foo@4", "FOO@8"), ranges("Foo foo FOO", FindQuery("foo")))
        assertEquals(listOf("foo@4"), ranges("Foo foo FOO", FindQuery("foo", caseSensitive = true)))
        assertEquals(listOf("a.b@4"), ranges("axb a.b", FindQuery("a.b")), "no regex meaning in plain mode")
        assertEquals(listOf("$(@4"), ranges("x = $(robot)", FindQuery("$(")))
        assertEquals(listOf("Äpfel@0", "äpfel@6"), ranges("Äpfel äpfel", FindQuery("äpfel")), "Unicode case folding")
    }

    @Test
    fun `an empty query matches nothing`() {
        val outcome = matcher.find("abc", FindQuery(""))
        assertEquals(emptyList(), outcome.matches)
        assertNull(outcome.error)
    }

    @Test
    fun `matches do not overlap`() {
        assertEquals(listOf("aa@0", "aa@2"), ranges("aaaaa", FindQuery("aa")))
    }

    @Test
    fun `whole word bounds the match by non-identifier characters`() {
        val text = "pos position _pos pos1 (pos) robot->pos"
        assertEquals(listOf("pos@0", "pos@24", "pos@36"), ranges(text, FindQuery("pos", wholeWord = true)))
        assertEquals(6, ranges(text, FindQuery("pos")).size)
    }

    @Test
    fun `regular expressions with line anchors and options`() {
        val text = "STATE a\n  STATE b\nSTATE c"
        assertEquals(listOf("STATE a@0", "STATE c@18"), ranges(text, FindQuery("^STATE \\w", regex = true)))
        assertEquals(listOf("STATE b@10"), ranges(text, FindQuery("(?<= )STATE b$", regex = true)))
        assertEquals(emptyList(), ranges(text, FindQuery("state", regex = true, caseSensitive = true)))
        assertEquals(listOf("b@16"), ranges(text, FindQuery("[a-c]", regex = true, wholeWord = true)).filter { it.startsWith("b") })
    }

    @Test
    fun `empty regex matches are skipped`() {
        assertEquals(listOf("aa@1", "a@4"), ranges("baabab", FindQuery("a*", regex = true)))
        assertEquals(emptyList(), ranges("abc", FindQuery("^", regex = true)))
    }

    @Test
    fun `an invalid regular expression is an error, not an exception`() {
        val outcome = matcher.find("abc", FindQuery("(ab", regex = true))
        assertEquals(emptyList(), outcome.matches)
        val error = assertNotNull(outcome.error)
        assertTrue("Unclosed group" in error, error)
        assertNotNull(matcher.find("abc", FindQuery("[", regex = true)).error)
        assertNotNull(matcher.find("abc", FindQuery("*", regex = true)).error)
        assertNull(matcher.find("(ab", FindQuery("(ab")).error, "plain mode never fails")
    }

    @Test
    fun `replacements are literal in plain mode`() {
        val text = "a b a"
        val matches = matcher.find(text, FindQuery("a")).matches
        assertEquals(listOf("$1", "$1"), matcher.replacements(text, FindQuery("a"), matches, "$1"))
    }

    @Test
    fun `regex replacements expand group references`() {
        val text = "x=1, y=22"
        val query = FindQuery("(\\w)=(\\d+)", regex = true)
        val matches = matcher.find(text, query).matches
        assertEquals(listOf("1=x", "22=y"), matcher.replacements(text, query, matches, "$2=$1"))
        val named = FindQuery("(?<key>\\w)=(?<value>\\d+)", regex = true)
        assertEquals(listOf("x:1", "y:22"), matcher.replacements(text, named, matches, "\${key}:\${value}"))
        assertEquals(listOf("$", "$"), matcher.replacements(text, query, matches, "\\$"), "an escaped dollar")
    }

    @Test
    fun `an unexpandable replacement is inserted literally`() {
        val text = "x=1, y=22"
        val query = FindQuery("(\\w)=(\\d+)", regex = true)
        val matches = matcher.find(text, query).matches
        assertEquals(listOf("$9", "$9"), matcher.replacements(text, query, matches, "$9"))
        assertEquals(listOf("a\\", "a\\"), matcher.replacements(text, query, matches, "a\\"))
    }

    @Test
    fun `replacements for a subset of matches`() {
        val text = "a1 a2 a3"
        val query = FindQuery("a(\\d)", regex = true)
        val second = listOf(TextRange(3, 5))
        assertEquals(listOf("<2>"), matcher.replacements(text, query, second, "<$1>"))
    }

    @Test
    fun `long searches check for cancellation`() {
        val text = "a".repeat(100_000)
        var checks = 0
        matcher.find(text, FindQuery("b")) { checks++ }
        assertTrue(checks > 0, "the callback is polled while matching")
        assertFailsWith<IllegalStateException> {
            matcher.find(text, FindQuery("(a+)+b", regex = true)) { error("cancelled") }
        }
    }

    @Test
    fun `random queries never throw on the examples`() {
        val random = Random(20261020)
        val alphabet = "ab()[]{}*+?|^$\\.-> \"#"
        for (file in Examples.files()) {
            repeat(20) {
                val query = FindQuery(
                    text = String(CharArray(random.nextInt(1, 6)) { alphabet[random.nextInt(alphabet.length)] }),
                    caseSensitive = random.nextBoolean(),
                    wholeWord = random.nextBoolean(),
                    regex = random.nextBoolean(),
                )
                val outcome = matcher.find(file.text, query)
                assertTrue(outcome.error == null || outcome.matches.isEmpty(), "${file.path} $query")
                outcome.matches.zipWithNext { a, b -> assertTrue(a.end <= b.start, "sorted, non-overlapping: $query") }
                assertTrue(outcome.matches.none { it.isEmpty }, "no empty matches: $query")
                val replaced = matcher.replacements(file.text, query, outcome.matches, "\$1x")
                assertEquals(outcome.matches.size, replaced.size)
            }
        }
    }
}
