package io.karpfen.ide.core.lang.symbols

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the document's own declarations by the staleness rule: latest parse first, the last good parse for what it
 * lost.
 */
class DocumentDeclarationsTest {

    private val path = Path.of("/ws/m.kstates")
    private val good = "STATEMACHINE ATTACHED TO \"R\" {\n STATES {\n  STATE \"a\" { }\n  STATE \"b\" { }\n }\n TRANSITIONS { }\n}\n"
    private val goodDocument = ExampleProject.document(path, good, version = 3)

    /** `STATE "a"` broken (its name is gone), so the latest parse lacks it while the last good one has it. */
    private val broken = ExampleProject.document(
        path,
        good.replace("STATE \"a\" { }", "STATE { }"),
        version = 5,
        lastGoodParse = goodDocument.parse,
    )

    @Test
    fun `a name only known from the last good parse is located with that parse's version and the document's lineage`() {
        val declarations = DocumentDeclarations(broken)

        val a = declarations.named(SymbolKind.STATE, "a").single()
        assertEquals(3L, a.location.version)
        assertSame(broken.text.lineage, a.location.lineage)
        assertEquals("a", goodDocument.text.substring(a.location.range))
        val b = declarations.named(SymbolKind.STATE, "b").single()
        assertEquals(5L, b.location.version, "known from the latest parse")
    }

    @Test
    fun `all declarations merge the latest parse with what only the last good parse knows`() {
        val names = DocumentDeclarations(broken).all(SymbolKind.STATE).map { it.declaration.name to it.location.version }

        assertEquals(listOf("b" to 5L, "a" to 3L), names)
    }

    @Test
    fun `the latest outcome prefers the parse of the current text`() {
        assertEquals(5L, DocumentDeclarations(broken).latest()?.outcome?.version)
        val lagging = broken.copy(text = TextSnapshot(broken.text.text, 9, broken.text.lineage))
        assertEquals(5L, DocumentDeclarations(lagging).latest()?.outcome?.version, "falls back to the latest parse")
        assertNull(DocumentDeclarations(broken.copy(parse = null, lastGoodParse = null)).latest())
    }

    @Test
    fun `a document without parse outcomes declares nothing`() {
        val declarations = DocumentDeclarations(goodDocument.copy(parse = null, lastGoodParse = null))

        assertTrue(declarations.all(SymbolKind.STATE).isEmpty())
        assertTrue(declarations.named(SymbolKind.STATE, "a").isEmpty())
        assertTrue(declarations.located().isEmpty())
    }
}
