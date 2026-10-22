package io.karpfen.ide.core.lang.services

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.brackets.TokenBracketMatcher
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.highlight.DefaultHighlightProvider
import io.karpfen.ide.core.lang.kmeta.KmetaLanguageFeatures
import io.karpfen.ide.core.lang.kmodel.KmodelKeyReferences
import io.karpfen.ide.core.lang.kmodel.KmodelLanguageFeatures
import io.karpfen.ide.core.lang.kstates.DefaultKstatesPathScopes
import io.karpfen.ide.core.lang.kstates.KstatesLanguageFeatures
import io.karpfen.ide.core.lang.kstates.KstatesSymbolReferences
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.navigation.TypeReferences
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import kotlinx.coroutines.flow.StateFlow

/**
 * The [LanguageServices] of one workspace: delegates to the [features] of each document's language and reads project
 * knowledge from [analysis] (the workspace's `Workspace.analysis`; tests pass a `MutableStateFlow` of a hand-built
 * analysis). Construction only wires objects.
 *
 * @param onProviderFailure called (on the calling thread) with every provider exception the facade caught and logged
 *   instead of letting it escape; the robustness tests pass a recorder and assert it stays empty, so the catch-all
 *   never hides a provider exception.
 * @throws IllegalArgumentException if two [features] declare the same language.
 */
public fun defaultLanguageServices(
    analysis: StateFlow<ProjectAnalysis>,
    features: List<LanguageFeatures> = karpfenLanguageFeatures(),
    onProviderFailure: (ProviderFailure) -> Unit = {},
): LanguageServices {
    val byLanguage = features.associateBy { it.language }
    require(byLanguage.size == features.size) { "duplicate languages in ${features.map { it.language }}" }
    return DefaultLanguageServices(analysis, byLanguage, onProviderFailure)
}

/**
 * The feature bundles of KMeta, KModel and KStates, sharing one caret-context detector, type catalog, model-path
 * resolver, KStates path scopes, cross-file reference search (types in every language, KModel keys, KStates paths and
 * event names), highlight provider and bracket matcher. Wiring only.
 */
public fun karpfenLanguageFeatures(): List<LanguageFeatures> {
    val caretContexts = CaretContextDetector()
    val types = TypeCatalog()
    val paths = ModelPathResolver()
    val scopes = DefaultKstatesPathScopes(types)
    val references = ContextReferences(listOf(TypeReferences(), KmodelKeyReferences(), KstatesSymbolReferences(scopes, paths)))
    val highlight = DefaultHighlightProvider()
    val brackets = TokenBracketMatcher()
    return listOf(
        KmetaLanguageFeatures(caretContexts, types, references, highlight, brackets),
        KmodelLanguageFeatures(caretContexts, types, references, highlight, brackets),
        KstatesLanguageFeatures(caretContexts, types, paths, scopes, references, highlight, brackets),
    )
}

/**
 * A provider exception that [defaultLanguageServices] caught: the facade [operation] (`highlight`, `complete`,
 * `definition`, `references`, `hover`, `matchingBracket`), the document's [language] and the [error].
 */
public data class ProviderFailure(
    public val operation: String,
    public val language: KarpfenLanguage,
    public val error: Throwable,
)
