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
package io.karpfen.ide.core.lang.modelpath

import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers splitting written model paths into segments with their ranges. */
class PathTextTest {

    @Test
    fun `segments are split at arrows with blanks trimmed`() {
        val text = "SET(\"a -> bb->c\")"
        val segments = pathSegments(text, TextRange(5, 15))

        assertEquals(listOf("a", "bb", "c"), segments.map { it.name })
        assertEquals(listOf("a", "bb", "c"), segments.map { text.substring(it.range.start, it.range.end) })
        assertEquals(1, segments.indexAt(text.indexOf("bb") + 2), "inclusive end")
        assertEquals("a->bb", segments.pathOf(2))
    }

    @Test
    fun `empty segments are kept, also at the end`() {
        assertEquals(listOf("a", ""), pathSegments("a->", TextRange(0, 3)).map { it.name })
        assertEquals(listOf(""), pathSegments("", TextRange(0, 0)).map { it.name })
        assertEquals(TextRange(3, 3), pathSegments("a->", TextRange(0, 3)).last().range)
    }

    @Test
    fun `ranges are clamped to the text`() {
        assertEquals(listOf("ab"), pathSegments("ab", TextRange(0, 10)).map { it.name })
    }

    @Test
    fun `reference segments come from the text, from a blank-free name or else span the reference`() {
        val withText = pathSegmentsOf("a->b", TextRange(2, 8), "x(a -> b)")
        assertEquals(listOf(TextRange(2, 3), TextRange(7, 8)), withText.map { it.range })

        val fromName = pathSegmentsOf("a->bc", TextRange(10, 15), text = null)
        assertEquals(listOf(TextRange(10, 11), TextRange(13, 15)), fromName.map { it.range })

        val spanning = pathSegmentsOf("a->b", TextRange(10, 17), text = null)
        assertEquals(listOf("a" to TextRange(10, 17), "b" to TextRange(10, 17)), spanning.map { it.name to it.range })
    }
}
