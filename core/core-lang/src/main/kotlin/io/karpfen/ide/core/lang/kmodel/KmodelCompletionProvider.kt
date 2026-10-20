package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.DocumentContext

/**
 * KModel completion (spec §5): object body → `prop("…") -> "…"`, `has("…") -> make object "…":"…" { }`, `knows("…")
 * -> "…"` snippets; inside `prop("` → simple properties of the enclosing object's type; inside `has("`/`knows("` →
 * relation keys of that type (has only for has, knows only for knows); `make object "k":"` → types (for a has value
 * the relation's target type first); `knows("r") -> "` → keys of objects in the same model whose type matches.
 */
internal class KmodelCompletionProvider(
    private val contexts: CaretContextDetector,
) : CompletionProvider {
    override fun complete(context: DocumentContext, offset: Int): List<CompletionItem> =
        TODO("WP-LANG: KModel completion")
}
