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

/**
 * The editor features of one language (Strategy + Abstract Factory, spec §1a, §5). `DefaultLanguageServices`
 * delegates every request to the features of the document's language. Implementations must honour the
 * `LanguageServices` contract (never throw is enforced by the facade as a last resort, but providers should not rely
 * on it).
 *
 * The product family of a language is split by layer: tokenizer and parser in core-document (`LanguageSupport`), the
 * features here; `karpfenLanguageFeatures()` must cover every language of `defaultLanguageRegistry()` (checked by a
 * contract test).
 */
public interface LanguageFeatures {
    /** The language these features serve. */
    public val language: KarpfenLanguage

    /** Lexical + semantic highlighting. */
    public val highlight: HighlightProvider

    /** Context-dependent completion. */
    public val completion: CompletionProvider

    /** Go to definition / find references. */
    public val navigation: NavigationProvider

    /** Hover information. */
    public val hover: HoverProvider

    /** Bracket matching. */
    public val brackets: BracketMatcher
}

/**
 * The input of every provider call: the document plus the project knowledge relevant to it.
 *
 * @property project the workspace's latest project analysis.
 * @property contextAnalysis the analysis of the modeling context containing the document, or null if it belongs to
 *   none.
 */
public data class FeatureRequest(
    public val document: LanguageDocument,
    public val project: ProjectAnalysis,
    public val contextAnalysis: ContextAnalysis?,
) {
    /**
     * True if the document is an event metamodel: the events file of its modeling context, or — outside a context —
     * a file named `EVENTS.kmeta` (spec §4).
     */
    public val isEventsDocument: Boolean
        get() = document.language == KarpfenLanguage.KMETA &&
            (
                contextAnalysis?.context?.events == document.path ||
                    FileKind.of(document.path.fileName?.toString().orEmpty()) == FileKind.EVENTS_KMETA
                )
}

/** Highlighting strategy (see `LanguageServices.highlight`). */
public fun interface HighlightProvider {
    /** Sorted, non-overlapping spans in the coordinates of `request.document.tokens.version`. */
    public fun highlight(request: FeatureRequest): List<HighlightSpan>
}

/** Completion strategy (see `LanguageServices.complete`). */
public fun interface CompletionProvider {
    /** Unfiltered proposals at [offset]. */
    public fun complete(request: FeatureRequest, offset: Int): List<CompletionItem>
}

/** Navigation strategy (see `LanguageServices.definition` and `references`). */
public interface NavigationProvider {
    /** Declarations of the symbol at [offset]. */
    public fun definition(request: FeatureRequest, offset: Int): List<Location>

    /** Declaration and references of the symbol at [offset]. */
    public fun references(request: FeatureRequest, offset: Int): List<Location>
}

/** Hover strategy (see `LanguageServices.hover`). */
public fun interface HoverProvider {
    /** Hover at [offset], or null. */
    public fun hover(request: FeatureRequest, offset: Int): Hover?
}

/** Bracket matching (see `LanguageServices.matchingBracket`). */
public fun interface BracketMatcher {
    /** The pair whose bracket touches [offset] in `request.document`, or null. */
    public fun match(request: FeatureRequest, offset: Int): BracketMatch?
}
