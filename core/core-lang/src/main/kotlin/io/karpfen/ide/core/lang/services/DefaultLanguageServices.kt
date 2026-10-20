package io.karpfen.ide.core.lang.services

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade: builds a `FeatureRequest` (document + `analysis.value` + the document's context analysis), picks the
 * features of the document's language and delegates; catches anything a provider throws, logs it (SLF4J), reports it
 * to [onProviderFailure] and returns the empty result instead. Languages without features yield empty results.
 */
internal class DefaultLanguageServices(
    private val analysis: StateFlow<ProjectAnalysis>,
    private val features: Map<KarpfenLanguage, LanguageFeatures>,
    private val onProviderFailure: (ProviderFailure) -> Unit,
) : LanguageServices {

    override fun highlight(doc: LanguageDocument): List<HighlightSpan> = TODO("WP-LANG: delegate highlight")

    override fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem> = TODO("WP-LANG: delegate completion")

    override fun definition(doc: LanguageDocument, offset: Int): List<Location> = TODO("WP-LANG: delegate definition")

    override fun references(doc: LanguageDocument, offset: Int): List<Location> = TODO("WP-LANG: delegate references")

    override fun hover(doc: LanguageDocument, offset: Int): Hover? = TODO("WP-LANG: delegate hover")

    override fun matchingBracket(doc: LanguageDocument, offset: Int): BracketMatch? = TODO("WP-LANG: delegate brackets")
}
