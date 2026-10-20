package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SymbolReference
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class QuotedNameErrorLocatorTest {

    private val locator: DslErrorLocator = DslErrorLocator()

    private val text = "// Domain.\ntype \"Robot\" \"r\" {\n    has(\"position\", \"Vector\")\n    knows(\"target\", \"Vector\")\n}"
    private val snapshot = TextSnapshot(text, 0)

    private fun inner(name: String, occurrence: Int = 0): TextRange {
        var start = -1
        repeat(occurrence + 1) { start = text.indexOf("\"$name\"", start + 1) }
        return TextRange.ofLength(start + 1, name.length)
    }

    private val whole = TextRange(0, text.length)

    private val index = SyntaxIndex(
        declarations = listOf(
            SymbolDeclaration(SymbolKind.TYPE, "Robot", inner("Robot"), whole, null),
            SymbolDeclaration(SymbolKind.HAS_RELATION, "position", inner("position"), whole, "Robot", "Vector"),
            SymbolDeclaration(SymbolKind.KNOWS_RELATION, "target", inner("target"), whole, "Robot", "Vector"),
        ),
        references = listOf(
            SymbolReference(ReferenceKind.TYPE, "Vector", inner("Vector"), null),
            SymbolReference(ReferenceKind.TYPE, "Vector", inner("Vector", 1), null),
        ),
    )

    private fun locate(message: String): String {
        val range = locator.locate(message, snapshot, index)
        return text.substring(range.start, range.end)
    }

    @Test
    fun `a message naming 'Vector' points at the first quoted Vector`() {
        assertEquals(inner("Vector"), locator.locate("Unknown target class type 'Vector' for property", snapshot, index))
        assertEquals(inner("Vector"), locator.locate("Unknown target class type \"Vector\"", snapshot, index))
    }

    @Test
    fun `the first symbol in document order wins, whatever the message order`() {
        assertEquals("Robot", locate("Unknown property 'target' for class type 'Robot'"))
        assertEquals(inner("target"), locator.locate("Unknown property 'target' in 'Nowhere'", snapshot, index))
    }

    @Test
    fun `unquoted, unknown or empty names fall back to the first token`() {
        val firstToken = TextRange(text.indexOf("type"), text.indexOf("type") + 4)
        assertEquals(firstToken, locator.locate("Exactly one state must be marked as INITIAL, but found 0.", snapshot, index))
        assertEquals(firstToken, locator.locate("Unknown type 'Nowhere' and Vector without quotes", snapshot, index))
        assertEquals(firstToken, locator.locate(null, snapshot, index))
        val anonymous = SyntaxIndex(listOf(SymbolDeclaration(SymbolKind.OBJECT, "", TextRange.empty(5), TextRange(0, 9), null)), emptyList())
        assertEquals(firstToken, locator.locate("Unknown class type for object ''", snapshot, anonymous))
    }

    @Test
    fun `the first token skips whitespace and comments and covers one word or character`() {
        fun first(source: String) = locator.locate("no names", TextSnapshot(source, 0), SyntaxIndex.EMPTY)

        assertEquals(TextRange(3, 15), first("  \nSTATEMACHINE ATTACHED"))
        assertEquals(TextRange(16, 20), first("// c1\n  // c2\n  make object"))
        assertEquals(TextRange(1, 2), first(" { }"))
        assertEquals(TextRange(0, 2), first("😀 x"), "a surrogate pair stays whole")
        assertEquals(TextRange.EMPTY, first(""))
        assertEquals(TextRange.EMPTY, first("  // only a comment\n\t"))
    }

    @Test
    fun `symbols of another, longer text are ignored`() {
        val short = TextSnapshot("type", 0)
        assertEquals(TextRange(0, 4), locator.locate("Unknown target class type 'Vector'", short, index))
    }
}
