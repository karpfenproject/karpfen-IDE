/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.core.lang.services

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.brackets.TokenBracketMatcher
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.environment.EnvironmentLanguageFeatures
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
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.flow.StateFlow

/**
 * The [LanguageServices] of one workspace: delegates to the [features] of each document's language and reads project
 * knowledge from [analysis] (the workspace's `Workspace.analysis`; tests pass a `MutableStateFlow` of a hand-built
 * analysis) and [files] (the workspace's `Workspace.files`, null where no file tree is known). Construction only wires
 * objects.
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
    files: StateFlow<WorkspaceTree>? = null,
): LanguageServices {
    val byLanguage = features.associateBy { it.language }
    require(byLanguage.size == features.size) { "duplicate languages in ${features.map { it.language }}" }
    return DefaultLanguageServices(analysis, byLanguage, onProviderFailure, files)
}

/**
 * The feature bundles of KMeta, KModel, KStates and environment descriptors. The modeling languages share one
 * caret-context detector, type catalog, model-path resolver, KStates path scopes, cross-file reference search (types in
 * every language, KModel keys, KStates paths and event names) and bracket matcher; every language shares the highlight
 * provider. Wiring only.
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
        EnvironmentLanguageFeatures(highlight),
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
