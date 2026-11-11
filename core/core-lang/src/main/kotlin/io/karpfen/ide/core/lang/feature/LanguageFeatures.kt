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
package io.karpfen.ide.core.lang.feature

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree

/**
 * The editor features of one language. They form a product family of strategies, built with the Strategy and Abstract
 * Factory patterns. So adding a language adds one implementation and changes no existing language class.
 * `DefaultLanguageServices` delegates every request to the features of the language of the document. Implementations
 * must honour the contract of `LanguageServices`. The facade catches what a provider throws as a last resort, but
 * providers should not rely on that.
 *
 * The product family of a language is split by layer. The tokenizer and parser live in core-document as
 * `LanguageSupport`, and the features live here. `karpfenLanguageFeatures()` must cover every language of
 * `defaultLanguageRegistry()`, and a test checks that.
 */
public interface LanguageFeatures {
    /** The language these features serve. */
    public val language: KarpfenLanguage

    /** Lexical and semantic highlighting. */
    public val highlight: HighlightProvider

    /** Context-dependent completion. */
    public val completion: CompletionProvider

    /** Go to definition and find references. */
    public val navigation: NavigationProvider

    /** Hover information. */
    public val hover: HoverProvider

    /** Bracket matching. */
    public val brackets: BracketMatcher
}

/**
 * The input of every provider call. It is the document together with the project knowledge that matters for it.
 *
 * @property project the latest project analysis of the workspace.
 * @property contextAnalysis the analysis of the modeling context that contains the document, or null if it belongs to
 *   none.
 * @property files the current file tree of the workspace. It is null where the services know none, such as in tests
 *   of a single document. Environment descriptors complete file paths from it, including files that are no karpfen
 *   source.
 */
public data class FeatureRequest(
    public val document: LanguageDocument,
    public val project: ProjectAnalysis,
    public val contextAnalysis: ContextAnalysis?,
    public val files: WorkspaceTree? = null,
) {
    /**
     * True if the document is an event metamodel. Inside a modeling context that is the events file of the context.
     * Outside a context it is a file named `EVENTS.kmeta`, compared ignoring case.
     */
    public val isEventsDocument: Boolean
        get() = document.language == KarpfenLanguage.KMETA &&
            (
                contextAnalysis?.context?.events == document.path ||
                    FileKind.of(document.path.fileName?.toString().orEmpty()) == FileKind.EVENTS_KMETA
                )
}

/** The highlighting strategy behind `LanguageServices.highlight`. */
public fun interface HighlightProvider {
    /** Sorted spans that do not overlap, in the coordinates of `request.document.tokens.version`. */
    public fun highlight(request: FeatureRequest): List<HighlightSpan>
}

/** The completion strategy behind `LanguageServices.complete`. */
public fun interface CompletionProvider {
    /** Unfiltered proposals at [offset]. */
    public fun complete(request: FeatureRequest, offset: Int): List<CompletionItem>
}

/** The navigation strategy behind `LanguageServices.definition` and `LanguageServices.references`. */
public interface NavigationProvider {
    /** Declarations of the symbol at [offset]. */
    public fun definition(request: FeatureRequest, offset: Int): List<Location>

    /** Declaration and references of the symbol at [offset]. */
    public fun references(request: FeatureRequest, offset: Int): List<Location>
}

/** The hover strategy behind `LanguageServices.hover`. */
public fun interface HoverProvider {
    /** Hover at [offset], or null. */
    public fun hover(request: FeatureRequest, offset: Int): Hover?
}

/** The bracket matching strategy behind `LanguageServices.matchingBracket`. */
public fun interface BracketMatcher {
    /** The pair whose bracket touches [offset] in `request.document`, or null. */
    public fun match(request: FeatureRequest, offset: Int): BracketMatch?
}
