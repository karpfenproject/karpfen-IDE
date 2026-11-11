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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.overlayHighlights
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [LanguageServices] fake for the editor's tests that does not depend on the providers of core-lang. Highlighting
 * follows the contract. Lexical styles come from the tokens, where each `TokenKind` maps to the `HighlightStyle` of the
 * same name. The semantic spans of the parse are laid over them only when the parse has the version of the tokens. The
 * other features answer what the test configures. The fake records the documents of definition and hover requests.
 *
 * @property completions the completion answer for a document and an offset.
 * @property definitions the definition answer for a document and an offset.
 * @property hovers the hover answer for a document and an offset.
 * @property brackets the bracket match for a document and an offset.
 */
class FakeLanguageServices(
    var completions: (LanguageDocument, Int) -> List<CompletionItem> = { _, _ -> emptyList() },
    var definitions: (LanguageDocument, Int) -> List<Location> = { _, _ -> emptyList() },
    var hovers: (LanguageDocument, Int) -> Hover? = { _, _ -> null },
    var brackets: (LanguageDocument, Int) -> BracketMatch? = { _, _ -> null },
) : LanguageServices {

    /** The documents definition requests were made for, in order. */
    val definitionRequests: MutableList<LanguageDocument> = CopyOnWriteArrayList()

    /** The documents hover requests were made for, in order. */
    val hoverRequests: MutableList<LanguageDocument> = CopyOnWriteArrayList()

    override fun highlight(doc: LanguageDocument): List<HighlightSpan> {
        val lexical = doc.tokens.tokens.map { HighlightSpan(it.range, HighlightStyle.valueOf(it.kind.name)) }
        val parse = doc.parse?.takeIf { it.version == doc.tokens.version } ?: return lexical
        val semantic = parse.semanticSpans.sortedBy { it.range.start }.map { HighlightSpan(it.range, HighlightStyle.valueOf(it.kind.name)) }
        return overlayHighlights(lexical, semantic)
    }

    override fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem> = completions(doc, offset)

    override fun definition(doc: LanguageDocument, offset: Int): List<Location> {
        definitionRequests += doc
        return definitions(doc, offset)
    }

    override fun references(doc: LanguageDocument, offset: Int): List<Location> = emptyList()

    override fun hover(doc: LanguageDocument, offset: Int): Hover? {
        hoverRequests += doc
        return hovers(doc, offset)
    }

    override fun matchingBracket(doc: LanguageDocument, offset: Int): BracketMatch? = brackets(doc, offset)
}
