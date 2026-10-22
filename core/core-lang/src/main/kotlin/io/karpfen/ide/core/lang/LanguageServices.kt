package io.karpfen.ide.core.lang

/**
 * Editor features for karpfen documents. Facade per workspace: the default implementation
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
 * - Ranges of highlight spans, completion items, hovers and bracket matches are in the request's text coordinates.
 *   Every [Location] carries the version and lineage of the outcome its range came from ([Location.version]: the
 *   request's text or current parse, the last good parse for declarations only known from it, the analyzed snapshot
 *   for other files), so navigation can map it into the live text.
 */
public interface LanguageServices {

    /**
     * Highlight spans in the coordinates of `doc.tokens.version`: lexical styles from the tokens, overlaid
     * ([overlayHighlights]) by the semantic styles from `doc.parse` when `parse.version == tokens.version`, lexical only
     * otherwise (the editor then carries the previous semantic layer forward itself). Sorted, non-overlapping;
     * whitespace has no span.
     */
    public fun highlight(doc: LanguageDocument): List<HighlightSpan>

    /**
     * Completion items at [offset], unfiltered (the editor filters by the text between `replaceRange.start` and the
     * caret) and in no particular order (the editor sorts). Context is determined from tokens plus the partial parse,
     * robust on broken text.
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
