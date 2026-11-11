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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path

/**
 * A document as [LanguageServices] see it. It holds immutable inputs only, so the services can run off the UI thread.
 *
 * Providers follow a staleness rule. [parse] and [lastGoodParse] may be older than [text]. Lookups by offset in a parse
 * outcome, such as `SyntaxIndex.referenceAt`, `declarationAt`, `enclosingDeclarations` and `OutlineNode.pathAt`, are
 * only made when the version of that outcome equals `text.version`. An older outcome is used only for knowledge by
 * name, such as "the states declared in this file". Context detection from tokens always works on [tokens]. Before
 * definition and hover, the editor waits a bounded time for a parse of the current version, so those normally see a
 * current parse.
 *
 * @property text the text the request refers to. Offsets are in its coordinates.
 * @property tokens the tokens. They have the version of [text] when [isConsistent] is true, as the contract on
 *   [LanguageServices] requires.
 * @property parse the latest parse outcome, possibly of an older version, or null.
 * @property lastGoodParse the latest parse outcome without syntax diagnostics, possibly older, or null. It keeps the
 *   declarations of the file known while the current text is broken.
 */
public data class LanguageDocument(
    public val path: Path,
    public val language: KarpfenLanguage,
    public val text: TextSnapshot,
    public val tokens: TokenizeResult,
    public val parse: ParseOutcome?,
    public val lastGoodParse: ParseOutcome? = null,
) {
    /**
     * True if [tokens] belong to [text]. Every [LanguageServices] method except `highlight` requires it.
     */
    public val isConsistent: Boolean get() = tokens.version == text.version

    /**
     * [parse] if it belongs to [text], else null. Lookups by offset use it, following the staleness rule on the class.
     */
    public val currentParse: ParseOutcome? get() = parse?.takeIf { it.version == text.version }

    public companion object {
        /**
         * [session] as seen at [text]. By default [text] is the current snapshot. The completion source passes the
         * snapshot a request was made for. The result holds the tokens of exactly that text from
         * `DocumentSession.tokensFor`, the latest parse and the last good parse. The parses may be of other versions,
         * following the staleness rule on the class. The result is always [isConsistent].
         *
         * @param text a snapshot of `session.text` with the same lineage.
         * @throws IllegalArgumentException if [text] is not a version of the session's text. That is a programming
         *   error.
         */
        public fun of(session: DocumentSession, text: TextSnapshot = session.text.snapshot.value): LanguageDocument {
            val tokens = session.tokensFor(text)
            return LanguageDocument(
                path = session.id.path,
                language = session.language,
                text = text,
                tokens = tokens,
                parse = session.parse.value,
                lastGoodParse = session.lastGoodParse.value,
            )
        }
    }
}

/**
 * A range in a file. It is the target of navigation, as for definitions, references, problems and the outline.
 *
 * @property version the version of the text that [range] was computed in, which is the version of the outcome the
 *   range came from. For tokens or the current parse it is the request's `text.version`. For a declaration only known
 *   from the last good parse it is the version of `lastGoodParse`. For another file of the modeling context it is
 *   `AnalyzedFile.snapshot.version`. For a problem it is the version of the snapshot of the diagnostics. It is null if
 *   unknown.
 * @property lineage the [TextLineage] in which [version] counts, or null if unknown. For the document's own parses it
 *   is the document's `text.lineage`, and for other files it is the lineage of the analyzed snapshot. Navigation maps
 *   the range into the current text of the open document with `EditableText.mapRange(range, version, lineage)`. That
 *   answers null for another lineage, a newer version or evicted history. Another lineage arises, for example, when the
 *   document was closed and reopened since. Navigation then clamps the range.
 */
public data class Location(
    public val path: Path,
    public val range: TextRange,
    public val version: Long? = null,
    public val lineage: TextLineage? = null,
) {
    init {
        require(lineage == null || version != null) { "a lineage needs a version" }
    }

    /** A location of [range] in [snapshot] of the file [path]. The version and the lineage come from [snapshot]. */
    public constructor(path: Path, range: TextRange, snapshot: TextSnapshot) :
        this(path, range, snapshot.version, snapshot.lineage)
}

/** Hover content for [range]. It is plain text without markup and may have several lines. */
public data class Hover(public val range: TextRange, public val text: String)

/** A matching bracket pair, given as the one-character ranges of the [open] and the [close] bracket. */
public data class BracketMatch(public val open: TextRange, public val close: TextRange)
