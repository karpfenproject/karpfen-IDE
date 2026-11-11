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
package io.karpfen.ide.core.lang

/**
 * Editor features for karpfen documents. There is one facade per workspace. The default implementation,
 * `io.karpfen.ide.core.lang.services.defaultLanguageServices`, picks the `LanguageFeatures` of the document's language
 * and delegates to them. Knowledge about the project, such as the other files of the modeling context, comes from the
 * project analysis of the workspace that the services were created with.
 *
 * Every method keeps this contract.
 * - It never throws, for any document text and any offset in `0..text.length`. Failures are logged and yield the
 *   empty result.
 * - It is thread-safe and has no side effects, so it may run on a background dispatcher.
 * - For [complete], [definition], [references], [hover] and [matchingBracket], the tokens of the document belong to
 *   its text, as [LanguageDocument.isConsistent] states and `LanguageDocument.of(session)` guarantees. Its parse
 *   outcomes may be older and are used by the staleness rule described on [LanguageDocument].
 * - The ranges of highlight spans, completion items, hovers and bracket matches are in the coordinates of the
 *   request's text. Every [Location] carries the version and the lineage of the outcome its range came from, so
 *   navigation can map it into the live text. [Location.version] explains which outcome that is.
 */
public interface LanguageServices {

    /**
     * Highlight spans in the coordinates of `doc.tokens.version`. The lexical styles come from the tokens. When
     * `parse.version == tokens.version`, the semantic styles from `doc.parse` are laid over them with
     * [overlayHighlights]. Otherwise the result is lexical only, and the editor carries the previous semantic layer
     * forward itself. The spans are sorted and do not overlap. Whitespace has no span.
     */
    public fun highlight(doc: LanguageDocument): List<HighlightSpan>

    /**
     * Completion items at [offset], unfiltered and in no particular order. The editor filters by the text between
     * `replaceRange.start` and the caret, and it sorts. The context is determined from the tokens and the partial
     * parse, so it works on broken text too.
     */
    public fun complete(doc: LanguageDocument, offset: Int): List<CompletionItem>

    /** The declarations of the symbol at [offset], possibly in other files of the modeling context. */
    public fun definition(doc: LanguageDocument, offset: Int): List<Location>

    /** The declaration and all references of the symbol at [offset] within the modeling context. */
    public fun references(doc: LanguageDocument, offset: Int): List<Location>

    /** The hover information for the symbol at [offset], or null. */
    public fun hover(doc: LanguageDocument, offset: Int): Hover?

    /** The bracket pair, `{}` or `()`, whose opening or closing bracket touches [offset], or null. */
    public fun matchingBracket(doc: LanguageDocument, offset: Int): BracketMatch?
}
