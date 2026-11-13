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
package io.karpfen.ide.core.document.frame

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextFrame
import io.karpfen.ide.core.text.TextFraming
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the parse of a piece of a KStates file with the real parser: it sees the piece in its place in the whole text
 * and gives the syntax errors and semantic spans back onto the piece.
 */
class FramedDocumentTest {

    private val support = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES)
    private val prefix = "STATEMACHINE ATTACHED TO \"Lamp\" {\n    STATES {\n        INITIAL STATE \"off\" {\n            ENTRY { "
    private val suffix = " }\n        }\n    }\n    TRANSITIONS { }\n}\n"

    /** The frame that puts a piece between [prefix] and [suffix], with the plain shift of positions. */
    private inner class Between(piece: String) : TextFrame {
        override val whole: String = prefix + piece + suffix
        override val region: TextRange = TextRange(prefix.length, prefix.length + piece.length)

        override fun toWhole(offset: Int): Int = prefix.length + offset

        override fun toPiece(range: TextRange): TextRange? {
            if (range.end < region.start || range.start > region.end) return null
            if (!range.isEmpty && (range.end == region.start || range.start == region.end)) return null
            return TextRange(maxOf(range.start, region.start) - prefix.length, minOf(range.end, region.end) - prefix.length)
        }
    }

    private fun document(framing: TextFraming = TextFraming { Between(it) }): FramedDocument =
        FramedDocument(Path.of("/project/m.kstates").toAbsolutePath(), support, framing)

    @Test
    fun `a piece is parsed in its place and gets the semantic spans of the whole text`() {
        val piece = "SET(\"mode\", \"off\")"
        val document = document()

        val outcome = document.support.parser.parse(TextSnapshot(piece, 4))

        assertEquals(4L, outcome.version)
        assertTrue(outcome.isSyntaxClean, "${outcome.syntaxDiagnostics}")
        assertEquals(listOf(SemanticKind.MODEL_PATH to "mode"), outcome.semanticSpans.map { it.kind to piece.substring(it.range.start, it.range.end) })
        val view = requireNotNull(document.view(TextSnapshot(piece, 4)))
        assertEquals(prefix + piece + suffix, view.snapshot.text)
        assertEquals(4L, view.parse?.version)
    }

    @Test
    fun `a syntax error in the piece stays at its place and one behind the piece stands at its end`() {
        val inside = "SET(\"mode\" \"off\")"
        val error = document().support.parser.parse(TextSnapshot(inside, 1)).syntaxDiagnostics.first()
        assertTrue(error.range.start >= inside.indexOf("\"off\"") - 1 && error.range.end <= inside.length, "$error")

        val open = "SET(\"mode\", \"off\""
        val pinned = document().support.parser.parse(TextSnapshot(open, 1)).syntaxDiagnostics
        assertTrue(pinned.isNotEmpty())
        assertTrue(pinned.all { it.range.end <= open.length }, "$pinned")
    }

    @Test
    fun `a piece that cannot be placed is coloured but not checked`() {
        val document = document(TextFraming { null })

        val outcome = document.support.parser.parse(TextSnapshot("SET(", 1))

        assertTrue(outcome.isSyntaxClean)
        assertNull(document.view(TextSnapshot("SET(", 1)))
    }
}
