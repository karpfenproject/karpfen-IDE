package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.DocumentContext
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver

/**
 * KStates completion (spec §5): structural keywords by position (STATE, INITIAL STATE, ENTRY, DO, TRANSITION, SPLIT,
 * JOIN, NOT LOOPING, CONDITION, EVENT, EVAL, VALUE, MACRO, TAKES, RETURNS, DEFINITION, action operations, IF IN
 * SCOPE, WITH … AS); state names inside transition strings; macro names inside `MACRO("`; `ATTACHED TO "` → domain
 * types; model paths in action/VALUE/IF IN SCOPE/macro-argument strings and after `$(` in EVAL (via
 * [ModelPathResolver]); event names in the second argument of `EVENT(` from `EVENTS.kmeta`.
 */
internal class KstatesCompletionProvider(
    private val contexts: CaretContextDetector,
    private val paths: ModelPathResolver,
) : CompletionProvider {
    override fun complete(context: DocumentContext, offset: Int): List<CompletionItem> =
        TODO("WP-LANG: KStates completion")
}
