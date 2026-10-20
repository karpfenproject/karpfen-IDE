package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KStates completion (spec §5): structural keywords by position (STATE, INITIAL STATE, ENTRY, DO, TRANSITION, SPLIT,
 * JOIN, NOT LOOPING, CONDITION, EVENT, EVAL, VALUE, MACRO, TAKES, RETURNS, DEFINITION, action operations, IF IN
 * SCOPE, WITH … AS); state names inside transition strings; macro names inside `MACRO("`; `ATTACHED TO "` → domain
 * types ([types]); model paths in action/VALUE/IF IN SCOPE/macro-argument strings and after `$(` in EVAL (via
 * [paths] in the scope of [scopes]); event names in the second argument of `EVENT(` from `EVENTS.kmeta`.
 */
internal class KstatesCompletionProvider(
    private val caretContexts: CaretContextDetector,
    private val paths: ModelPathResolver,
    private val types: TypeCatalog,
    private val scopes: KstatesPathScopes,
) : CompletionProvider {
    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> =
        TODO("WP-LANG: KStates completion")
}
