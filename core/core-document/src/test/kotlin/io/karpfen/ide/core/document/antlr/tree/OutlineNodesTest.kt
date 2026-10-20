package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.pathAt
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class OutlineNodesTest {

    @Test
    fun `a node covers its selection and its children`() {
        val child = outlineNode(SymbolKind.STATE, "c", null, TextRange(40, 50), TextRange(41, 42))

        val node = outlineNode(SymbolKind.STATE, "p", null, TextRange(10, 20), TextRange(5, 6), listOf(child))

        assertEquals(TextRange(5, 50), node.range)
        assertEquals(TextRange(5, 6), node.selectionRange)
        assertEquals(listOf("p", "c"), listOf(node).pathAt(45).map { it.name })
    }

    @Test
    fun `a node that needs no widening keeps its range`() {
        val node = outlineNode(SymbolKind.TYPE, "T", "d", TextRange(0, 30), TextRange(6, 7))

        assertEquals(TextRange(0, 30), node.range)
        assertEquals(emptyList(), node.children)
    }
}
