package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KMeta completion (spec §5): top level → `type "Name" "Description" { }` snippet; in a type body → `prop`, `has`,
 * `knows`, `list` snippets; second argument of `prop(` → `number string boolean`; type argument of
 * `has(`/`knows(`/`list(` → the types of [types] (declared in the file plus, when editing `EVENTS.kmeta`, the
 * context's domain types).
 */
internal class KmetaCompletionProvider(
    private val caretContexts: CaretContextDetector,
    private val types: TypeCatalog,
) : CompletionProvider {
    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> =
        TODO("WP-LANG: KMeta completion")
}
