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
package io.karpfen.ide.core.lang.services

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.frame.FramedDocument
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextFrame
import io.karpfen.ide.core.text.TextFraming
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests the language services of a piece of a file: what they ask the services of the workspace, and how the answers
 * come back onto the piece.
 */
class FramedLanguageServicesTest {

    private val file: Path = Path.of("/project/lamp.kstates").toAbsolutePath()
    private val support = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES)
    private val prefix = "STATEMACHINE ATTACHED TO \"Lamp\" {\n    STATES {\n        STATE \"off\" {\n            ENTRY { "
    private val suffix = " }\n        }\n    }\n}\n"
    private val piece = "SET(\"mode\", \"off\")"

    /** Language services that record each request and answer what the test programs. */
    private class ProgrammedServices : LanguageServices {
        val requests = ArrayList<Pair<LanguageDocument, Int>>()
        var completions: List<CompletionItem> = emptyList()
        var hover: Hover? = null

        override fun highlight(doc: LanguageDocument): List<HighlightSpan> = emptyList()

        override fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem> {
            requests += doc to offset
            return completions
        }

        override fun definition(doc: LanguageDocument, offset: Int): List<Location> = emptyList()

        override fun references(doc: LanguageDocument, offset: Int): List<Location> = emptyList()

        override fun hover(doc: LanguageDocument, offset: Int): Hover? {
            requests += doc to offset
            return hover
        }

        override fun matchingBracket(doc: LanguageDocument, offset: Int): BracketMatch? = null
    }

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

    private val inner = ProgrammedServices()

    private fun services(framing: TextFraming = TextFraming { Between(it) }): LanguageServices =
        framedLanguageServices(inner, FramedDocument(file, support, framing))

    private fun pieceDoc(): LanguageDocument {
        val snapshot = TextSnapshot(piece, 3)
        return LanguageDocument(Path.of("/project/.cell.lamp.kstates"), KarpfenLanguage.KSTATES, snapshot, support.tokenizer.tokenize(snapshot), null)
    }

    private fun item(range: TextRange): CompletionItem =
        CompletionItem("mode", CompletionKind.PROPERTY, "mode", range, null, null, null, "mode")

    @Test
    fun `completion asks about the whole text under the path of the file at the mapped offset`() {
        services().complete(pieceDoc(), 5)

        val (doc, offset) = inner.requests.single()
        assertEquals(file, doc.path)
        assertEquals(prefix + piece + suffix, doc.text.text)
        assertEquals(3L, doc.text.version)
        assertEquals(prefix.length + 5, offset)
        assertEquals(doc.text.version, doc.tokens.version, "the tokens are those of the whole text")
    }

    @Test
    fun `completions come back onto the piece and those that leave it are dropped`() {
        val inside = prefix.length + 5
        inner.completions = listOf(
            item(TextRange(inside, inside + 4)),
            item(TextRange(prefix.length - 2, prefix.length + 3)),
            item(TextRange(prefix.length + piece.length, prefix.length + piece.length + 2)),
        )

        val answered = services().complete(pieceDoc(), 9)

        assertEquals(listOf(TextRange(5, 9)), answered.map { it.replaceRange })
    }

    @Test
    fun `a hover comes back onto the piece, clipped, and one outside the piece is dropped`() {
        inner.hover = Hover(TextRange(prefix.length - 3, prefix.length + 3), "the action SET")
        assertEquals(Hover(TextRange(0, 3), "the action SET"), services().hover(pieceDoc(), 1))
        assertEquals(prefix.length + 1, inner.requests.single().second)

        inner.hover = Hover(TextRange(0, 5), "STATEMACHINE")
        assertNull(services().hover(pieceDoc(), 1))
    }

    @Test
    fun `a piece that cannot be framed is asked about as it is`() {
        val doc = pieceDoc()
        inner.completions = listOf(item(TextRange(5, 9)))

        val answered = services(TextFraming { null }).complete(doc, 5)

        assertSame(doc, inner.requests.single().first)
        assertEquals(5, inner.requests.single().second)
        assertEquals(listOf(TextRange(5, 9)), answered.map { it.replaceRange })
    }
}
