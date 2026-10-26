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
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Facade: builds a `FeatureRequest` (document + `analysis.value` + the document's context analysis + `files.value`),
 * picks the features of the document's language and delegates; catches anything a provider throws, logs it (SLF4J), reports it
 * to [onProviderFailure] and returns the empty result instead. Languages without features yield empty results.
 *
 * Preconditions of the `LanguageServices` contract are checked instead of trusted, so a caller's mistake cannot reach a
 * provider: an offset outside `0..text.length`, and — for every operation but `highlight` — tokens of another version
 * than the text ([LanguageDocument.isConsistent]), yield the empty result (logged at debug level, not a provider
 * failure).
 */
internal class DefaultLanguageServices(
    private val analysis: StateFlow<ProjectAnalysis>,
    private val features: Map<KarpfenLanguage, LanguageFeatures>,
    private val onProviderFailure: (ProviderFailure) -> Unit,
    private val files: StateFlow<WorkspaceTree>?,
) : LanguageServices {

    override fun highlight(doc: LanguageDocument): List<HighlightSpan> =
        delegate("highlight", doc, offset = null, emptyList()) { features, request -> features.highlight.highlight(request) }

    override fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem> =
        delegate("complete", doc, offset, emptyList()) { features, request -> features.completion.complete(request, offset) }

    override fun definition(doc: LanguageDocument, offset: Int): List<Location> =
        delegate("definition", doc, offset, emptyList()) { features, request -> features.navigation.definition(request, offset) }

    override fun references(doc: LanguageDocument, offset: Int): List<Location> =
        delegate("references", doc, offset, emptyList()) { features, request -> features.navigation.references(request, offset) }

    override fun hover(doc: LanguageDocument, offset: Int): Hover? =
        delegate("hover", doc, offset, null) { features, request -> features.hover.hover(request, offset) }

    override fun matchingBracket(doc: LanguageDocument, offset: Int): BracketMatch? =
        delegate("matchingBracket", doc, offset, null) { features, request -> features.brackets.match(request, offset) }

    /**
     * Runs [call] with the features of [doc]'s language and a request for it; [empty] if there are no features, a
     * precondition does not hold ([offset] null: `highlight`, which needs no consistent tokens) or the provider throws.
     */
    private fun <T> delegate(
        operation: String,
        doc: LanguageDocument,
        offset: Int?,
        empty: T,
        call: (LanguageFeatures, FeatureRequest) -> T,
    ): T {
        val bundle = features[doc.language] ?: return empty
        if (offset != null && (offset !in 0..doc.text.length || !doc.isConsistent)) {
            LOG.debug(
                "{} skipped for {}: offset {} of {}, tokens of version {} for text version {}",
                operation, doc.path, offset, doc.text.length, doc.tokens.version, doc.text.version,
            )
            return empty
        }
        return try {
            val project = analysis.value
            call(bundle, FeatureRequest(doc, project, project.contextOf(doc.path), files?.value))
        } catch (e: Exception) {
            report(operation, doc, e)
            empty
        } catch (e: StackOverflowError) {
            report(operation, doc, e)
            empty
        }
    }

    /** Logs a provider failure and reports it to [onProviderFailure], which must not throw either. */
    private fun report(operation: String, doc: LanguageDocument, error: Throwable) {
        LOG.warn("Language provider failed: {} for {} ({})", operation, doc.path, doc.language, error)
        try {
            onProviderFailure(ProviderFailure(operation, doc.language, error))
        } catch (e: Exception) {
            LOG.warn("onProviderFailure threw while reporting a {} failure", operation, e)
        }
    }

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(DefaultLanguageServices::class.java)
    }
}
