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

/**
 * The editor features of one language (Strategy + Abstract Factory, spec §1a, §5). `DefaultLanguageServices`
 * delegates every request to the features of the document's language. Implementations must honour the
 * `LanguageServices` contract (never throw is enforced by the facade as a last resort, but providers should not rely
 * on it).
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
 * @property context the analysis of the modeling context containing the document, or null if it belongs to none.
 */
public data class DocumentContext(
    public val document: LanguageDocument,
    public val project: ProjectAnalysis,
    public val context: ContextAnalysis?,
)

/** Highlighting strategy (see `LanguageServices.highlight`). */
public fun interface HighlightProvider {
    /** Sorted, non-overlapping spans in the coordinates of `document.tokens.version`. */
    public fun highlight(context: DocumentContext): List<HighlightSpan>
}

/** Completion strategy (see `LanguageServices.complete`). */
public fun interface CompletionProvider {
    /** Unfiltered proposals at [offset]. */
    public fun complete(context: DocumentContext, offset: Int): List<CompletionItem>
}

/** Navigation strategy (see `LanguageServices.definition` and `references`). */
public interface NavigationProvider {
    /** Declarations of the symbol at [offset]. */
    public fun definition(context: DocumentContext, offset: Int): List<Location>

    /** Declaration and references of the symbol at [offset]. */
    public fun references(context: DocumentContext, offset: Int): List<Location>
}

/** Hover strategy (see `LanguageServices.hover`). */
public fun interface HoverProvider {
    /** Hover at [offset], or null. */
    public fun hover(context: DocumentContext, offset: Int): Hover?
}

/** Bracket matching (see `LanguageServices.matchingBracket`). */
public fun interface BracketMatcher {
    /** The pair whose bracket touches [offset] in [document], or null. */
    public fun match(document: LanguageDocument, offset: Int): BracketMatch?
}
