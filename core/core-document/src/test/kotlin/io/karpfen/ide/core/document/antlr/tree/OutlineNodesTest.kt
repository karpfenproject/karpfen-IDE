/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.pathAt
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [outlineNode]: a node's range is widened to cover its selection and its children, and only when needed.
 */
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
