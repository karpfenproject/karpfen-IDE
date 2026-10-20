package io.karpfen.ide.core.lang.services

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.brackets.TokenBracketMatcher
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.highlight.DefaultHighlightProvider
import io.karpfen.ide.core.lang.kmeta.KmetaLanguageFeatures
import io.karpfen.ide.core.lang.kmodel.KmodelLanguageFeatures
import io.karpfen.ide.core.lang.kstates.KstatesLanguageFeatures
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import kotlinx.coroutines.flow.StateFlow

/**
 * The [LanguageServices] of one workspace: delegates to the [features] of each document's language and reads project
 * knowledge from [analysis] (the workspace's `Workspace.analysis`; tests pass a `MutableStateFlow` of a hand-built
 * analysis). Construction only wires objects.
 *
 * @throws IllegalArgumentException if two [features] declare the same language.
 */
public fun defaultLanguageServices(
    analysis: StateFlow<ProjectAnalysis>,
    features: List<LanguageFeatures> = karpfenLanguageFeatures(),
): LanguageServices {
    val byLanguage = features.associateBy { it.language }
    require(byLanguage.size == features.size) { "duplicate languages in ${features.map { it.language }}" }
    return DefaultLanguageServices(analysis, byLanguage)
}

/**
 * The feature bundles of KMeta, KModel and KStates, sharing one caret-context detector, model-path resolver,
 * highlight provider and bracket matcher. Wiring only.
 */
public fun karpfenLanguageFeatures(): List<LanguageFeatures> {
    val contexts = CaretContextDetector()
    val paths = ModelPathResolver()
    val highlight = DefaultHighlightProvider()
    val brackets = TokenBracketMatcher()
    return listOf(
        KmetaLanguageFeatures(contexts, highlight, brackets),
        KmodelLanguageFeatures(contexts, highlight, brackets),
        KstatesLanguageFeatures(contexts, paths, highlight, brackets),
    )
}
