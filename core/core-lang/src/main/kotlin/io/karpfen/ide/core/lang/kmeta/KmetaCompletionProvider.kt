package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.DocumentContext

/**
 * KMeta completion (spec §5): top level → `type "Name" "Description" { }` snippet; in a type body → `prop`, `has`,
 * `knows`, `list` snippets; second argument of `prop(` → `number string boolean`; type argument of
 * `has(`/`knows(`/`list(` → types declared in the file plus, when editing `EVENTS.kmeta`, the context's domain types.
 */
internal class KmetaCompletionProvider(
    private val contexts: CaretContextDetector,
) : CompletionProvider {
    override fun complete(context: DocumentContext, offset: Int): List<CompletionItem> =
        TODO("WP-LANG: KMeta completion")
}
