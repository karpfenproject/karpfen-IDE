package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.modelpath.PathScope
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * The model-path scope at an offset of a KStates document (spec §5): one collaborator shared by KStates completion,
 * hover and navigation instead of three copies.
 *
 * - `types`: `TypeCatalog.types(request)` (domain and event types);
 * - `rootType`: the `ATTACHED TO` type of the STATEMACHINE;
 * - `eventType`: the event type named by the enclosing TRANSITION's `EVENT(…)` clause when determinable, else null;
 * - `bindings`: `WITH MACRO(…) AS "x"` bindings in scope (typed by the macro's RETURNS type) and, inside a MACRO
 *   definition, its TAKES parameters (typed by their declared types).
 *
 * Works on the document's tokens and current parse (staleness rule of `LanguageDocument`); never throws.
 */
internal fun interface KstatesPathScopes {
    /** The scope for a model path at [offset] of `request.document`. */
    fun scopeAt(request: FeatureRequest, offset: Int): PathScope
}

/** Default [KstatesPathScopes] over the syntax index and [types]. */
internal class DefaultKstatesPathScopes(private val types: TypeCatalog) : KstatesPathScopes {
    override fun scopeAt(request: FeatureRequest, offset: Int): PathScope =
        TODO("WP-LANG: KStates path scope at $offset (root type, event type, bindings)")
}
