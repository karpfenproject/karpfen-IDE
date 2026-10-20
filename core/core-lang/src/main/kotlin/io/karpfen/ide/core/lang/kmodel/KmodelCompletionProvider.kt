package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KModel completion (spec §5): object body → `prop("…") -> "…"`, `has("…") -> make object "…":"…" { }`, `knows("…")
 * -> "…"` snippets; inside `prop("` → simple properties of the enclosing object's type; inside `has("`/`knows("` →
 * relation keys of that type (has only for has, knows only for knows); `make object "k":"` → types (for a has value
 * the relation's target type first); `knows("r") -> "` → keys of objects in the same model whose type matches (object
 * types from `SymbolDeclaration.typeName`, members from [types]).
 */
internal class KmodelCompletionProvider(
    private val caretContexts: CaretContextDetector,
    private val types: TypeCatalog,
) : CompletionProvider {
    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> =
        TODO("WP-LANG: KModel completion")
}
