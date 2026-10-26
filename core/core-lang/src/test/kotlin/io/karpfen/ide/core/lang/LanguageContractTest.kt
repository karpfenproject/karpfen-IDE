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
package io.karpfen.ide.core.lang

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.lang.modelpath.PathMember
import io.karpfen.ide.core.lang.modelpath.PathOrigin
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.services.karpfenLanguageFeatures
import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the complete values and wiring of core-lang on their own: every registered language has feature providers,
 * the member signature and type hover formats, the consistency of documents, the semantic styles, and the overlay rule
 * of highlighting.
 */
class LanguageContractTest {

    @Test
    fun `every registered language has feature providers`() {
        val features = karpfenLanguageFeatures()

        assertEquals(defaultLanguageRegistry().languages, features.map { it.language }.toSet())
        assertEquals(features.size, features.map { it.language }.toSet().size, "one bundle per language")
    }

    @Test
    fun `member signatures and type hovers use the spec format`() {
        val position = MemberInfo("position", MemberKind.HAS, "Vector", isList = false, declaration = null)
        val obstacles = MemberInfo("obstacles", MemberKind.KNOWS, "Obstacle", isList = true, declaration = null)
        val x = MemberInfo("x", MemberKind.PROPERTY, "number", isList = false, declaration = null)

        assertEquals("has position: Vector", position.signature)
        assertEquals("knows obstacles: list(Obstacle)", obstacles.signature)
        assertEquals("prop x: number", x.signature)
        assertTrue(x.isPrimitive)
        assertEquals(
            "type Robot\nA cleaning robot\n\nprop x: number\nhas position: Vector",
            TypeInfo("Robot", "A cleaning robot", listOf(x, position), TypeOrigin.DOMAIN, declaration = null).hoverText,
        )
        assertEquals("type Empty", TypeInfo("Empty", "", emptyList(), TypeOrigin.DOCUMENT, declaration = null).hoverText)

        val segment = PathMember.of(position)
        assertEquals(MemberKind.HAS, segment.kind)
        assertEquals(PathType("Vector", isList = false, isPrimitive = false), segment.type)
        assertNull(PathMember("self", PathOrigin.ROOT, PathType("Robot", isList = false, isPrimitive = false)).kind)
        assertFailsWith<IllegalArgumentException> { PathMember("x", PathOrigin.MEMBER, PathType.of(x)) }
    }

    @Test
    fun `documents know whether their tokens belong to their text`() {
        val text = TextSnapshot("STATE", 3)
        val path = Path.of("/ws/robot.kstates").toAbsolutePath()

        assertTrue(LanguageDocument(path, KarpfenLanguage.KSTATES, text, TokenizeResult.empty(3), parse = null).isConsistent)
        val lagging = LanguageDocument(path, KarpfenLanguage.KSTATES, text, TokenizeResult.empty(2), parse = null)
        assertFalse(lagging.isConsistent)
        assertNull(lagging.currentParse)
        assertNull(Location(path, TextRange(0, 5)).version)
        val located = Location(path, TextRange(0, 5), text)
        assertEquals(3L, located.version)
        assertTrue(located.lineage === text.lineage)
        assertFailsWith<IllegalArgumentException> { Location(path, TextRange(0, 5), version = null, lineage = text.lineage) }
        assertFailsWith<IllegalArgumentException> {
            CompletionItem("x", CompletionKind.KEYWORD, "x", TextRange.EMPTY, 2, null, null, "x")
        }
    }

    @Test
    fun `the semantic styles are exactly the semantic kinds`() {
        val semantic = HighlightStyle.entries.filter { it.isSemantic }.map { it.name }.toSet()

        assertEquals(SemanticKind.entries.map { it.name }.toSet(), semantic)
    }

    @Test
    fun `overlays win inside their ranges and split what they cover`() {
        fun span(start: Int, end: Int, style: HighlightStyle) = HighlightSpan(TextRange(start, end), style)
        val base = listOf(
            span(0, 4, HighlightStyle.KEYWORD),
            span(5, 15, HighlightStyle.STRING),
            span(16, 17, HighlightStyle.PUNCTUATION),
        )
        val overlay = listOf(
            span(6, 10, HighlightStyle.STATE_NAME),
            span(12, 17, HighlightStyle.MODEL_PATH),
            span(20, 20, HighlightStyle.TYPE_NAME),
        )

        assertEquals(
            listOf(
                span(0, 4, HighlightStyle.KEYWORD),
                span(5, 6, HighlightStyle.STRING),
                span(6, 10, HighlightStyle.STATE_NAME),
                span(10, 12, HighlightStyle.STRING),
                span(12, 17, HighlightStyle.MODEL_PATH),
            ),
            overlayHighlights(base, overlay),
        )
        assertEquals(base, overlayHighlights(base, emptyList()))
        val type = span(1, 2, HighlightStyle.TYPE_NAME)
        assertEquals(listOf(type), overlayHighlights(emptyList(), listOf(type)))
        val description = span(5, 15, HighlightStyle.DESCRIPTION)
        assertEquals(listOf(description), overlayHighlights(listOf(span(5, 15, HighlightStyle.STRING)), listOf(description)))
    }
}
