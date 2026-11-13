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

import io.karpfen.ide.core.document.frame.FramedDocument
import io.karpfen.ide.core.document.frame.WholeView
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location

/**
 * The [LanguageServices] of a session that edits a piece of a file, as a [FramedDocument] describes. It decorates the
 * services of the workspace, [inner], and asks them about the whole text of the file instead of the piece.
 *
 * Completion and hover put the piece in its place in the whole text with [document], ask [inner] about that whole text
 * under the path of the file at the place of the request, and map the answer back onto the piece. A completion whose
 * replace range does not lie in the piece is left out, and so is a hover whose range lies outside it. A request about
 * a piece that cannot be placed goes to [inner] as it is, so such a piece gets what any document gets.
 *
 * Highlighting, go to definition, references and bracket matching go to [inner] with the document of the piece. The
 * semantic colours already come from the parse of the piece, which carries those of the whole text. A definition or a
 * reference would lead into a text that does not exist until the piece is saved, so the piece answers them as any
 * document on its own does.
 */
internal class FramedLanguageServices(
    private val inner: LanguageServices,
    private val document: FramedDocument,
) : LanguageServices {

    override fun highlight(doc: LanguageDocument): List<HighlightSpan> = inner.highlight(doc)

    override fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem> {
        val view = document.view(doc.text) ?: return inner.complete(doc, offset)
        return inner.complete(wholeOf(doc, view), view.frame.toWhole(offset)).mapNotNull { item ->
            item.replaceRange.takeIf { it in view.frame.region }
                ?.let(view.frame::toPiece)
                ?.let { item.copy(replaceRange = it) }
        }
    }

    override fun definition(doc: LanguageDocument, offset: Int): List<Location> = inner.definition(doc, offset)

    override fun references(doc: LanguageDocument, offset: Int): List<Location> = inner.references(doc, offset)

    override fun hover(doc: LanguageDocument, offset: Int): Hover? {
        val view = document.view(doc.text) ?: return inner.hover(doc, offset)
        val hover = inner.hover(wholeOf(doc, view), view.frame.toWhole(offset)) ?: return null
        return view.frame.toPiece(hover.range)?.let { hover.copy(range = it) }
    }

    override fun matchingBracket(doc: LanguageDocument, offset: Int): BracketMatch? = inner.matchingBracket(doc, offset)

    /** The document of the whole text of [view], under the path of the file and in the language of [doc]. */
    private fun wholeOf(doc: LanguageDocument, view: WholeView): LanguageDocument =
        LanguageDocument(document.path, doc.language, view.snapshot, view.tokens, view.parse, view.lastGoodParse)
}

/**
 * The language services of a session that edits a piece of a file: [inner], the services of the workspace, seen
 * through [document], as [FramedLanguageServices] describes. The function only wires objects.
 */
public fun framedLanguageServices(inner: LanguageServices, document: FramedDocument): LanguageServices =
    FramedLanguageServices(inner, document)
