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
 * A [LanguageServices] fake for the editor's tests (WP-LANG is developed in parallel): highlighting follows the
 * contract — lexical styles from the tokens (`TokenKind` → the `HighlightStyle` of the same name), overlaid with the
 * semantic spans of the parse only when the parse has the tokens' version — and the other features answer what the
 * test configures. Records the documents of definition and hover requests.
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
