package io.karpfen.ide.core.lang.highlight

import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HighlightProvider

/**
 * Language-neutral highlighting shared by all languages: maps every `TokenKind` to a `HighlightStyle` and, when the
 * parse outcome has the tokens' version, overlays its `SemanticSpan`s (`SemanticKind` → the `HighlightStyle` of the
 * same name, all `isSemantic`) with `overlayHighlights` (splitting lexical spans where a semantic span covers part of
 * them, e.g. the name inside a string's quotes). Result sorted and non-overlapping.
 */
internal class DefaultHighlightProvider : HighlightProvider {
    override fun highlight(request: FeatureRequest): List<HighlightSpan> =
        TODO("WP-LANG: lexical styles overlaid with semantic spans")
}
