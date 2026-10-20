package io.karpfen.ide.core.lang

/**
 * Editor features for karpfen documents (spec §5). Facade per workspace: the default implementation
 * (`io.karpfen.ide.core.lang.services.defaultLanguageServices`) picks the `LanguageFeatures` of the document's
 * language and delegates; project knowledge (other files of the modeling context) comes from the workspace's project
 * analysis it was created with.
 *
 * Contract (MUST), for every method:
 * - Never throws, for any document text and any offset in `0..text.length`; failures are logged and yield the empty
 *   result.
 * - Thread-safe and side-effect free; may run on a background dispatcher.
 * - For [complete], [definition], [references], [hover] and [matchingBracket] the document's tokens belong to its
 *   text ([LanguageDocument.isConsistent]; `LanguageDocument.of(session)` guarantees it); its parse outcomes may be
 *   older and are used by the staleness rule documented on [LanguageDocument].
 * - Ranges in results are in the request's text coordinates, except [Location]s in other files, which carry the
 *   version of the analyzed snapshot they refer to ([Location.version]).
 */
public interface LanguageServices {

    /**
     * Highlight spans in the coordinates of `doc.tokens.version`: lexical styles from the tokens, overlaid by semantic
     * styles from `doc.parse` when `parse.version == tokens.version`. Sorted, non-overlapping; whitespace has no span.
     */
    public fun highlight(doc: LanguageDocument): List<HighlightSpan>

    /**
     * Completion items at [offset], unfiltered (the editor filters by the text between `replaceRange.start` and the
     * caret) and in no particular order (the editor sorts). Context is determined from tokens plus the partial parse,
     * robust on broken text (spec §5, Completion).
     */
    public fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem>

    /** Declarations of the symbol at [offset], possibly in other files of the modeling context. */
    public fun definition(doc: LanguageDocument, offset: Int): List<Location>

    /** Declaration and all references of the symbol at [offset] within the modeling context. */
    public fun references(doc: LanguageDocument, offset: Int): List<Location>

    /** Hover information for the symbol at [offset], or null. */
    public fun hover(doc: LanguageDocument, offset: Int): Hover?

    /** The bracket pair (`{}`, `()`) whose opening or closing bracket touches [offset], or null. */
    public fun matchingBracket(doc: LanguageDocument, offset: Int): BracketMatch?
}
