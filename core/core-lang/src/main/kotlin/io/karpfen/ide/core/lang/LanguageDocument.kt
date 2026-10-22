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
 * A document as seen by [LanguageServices]: immutable inputs only, so services can run off the UI thread.
 *
 * Staleness rule for the providers (MUST): [parse] and [lastGoodParse] may be older than [text]. Offset-based lookups
 * in a parse outcome (`SyntaxIndex.referenceAt`, `declarationAt`, `enclosingDeclarations`, `OutlineNode.pathAt`) are
 * only made when that outcome's version equals `text.version`; an older outcome is used for name-based knowledge only
 * (e.g. "the states declared in this file"). Token-based context detection always works on [tokens]. The editor waits
 * (bounded) for a parse of the current version before definition and hover, so those normally see a current parse.
 *
 * @property text the text the request refers to (offsets are in its coordinates).
 * @property tokens tokens, of [text]'s version when [isConsistent] (see the [LanguageServices] contract).
 * @property parse the latest parse outcome, possibly of an older version, or null.
 * @property lastGoodParse the latest parse outcome without syntax diagnostics, possibly older, or null; it keeps the
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
    /** True if [tokens] belong to [text] (required by every [LanguageServices] method but `highlight`). */
    public val isConsistent: Boolean get() = tokens.version == text.version

    /** [parse] if it belongs to [text], else null (for offset-based lookups, see the staleness rule). */
    public val currentParse: ParseOutcome? get() = parse?.takeIf { it.version == text.version }

    public companion object {
        /**
         * [session] as seen at [text] (default: its current snapshot; the completion source passes the snapshot a
         * request was made for): the tokens of exactly that text (`DocumentSession.tokensFor`), the latest parse and the
         * last good parse (possibly of other versions, see the staleness rule). The result is always [isConsistent].
         *
         * @param text a snapshot of `session.text` (same lineage).
         * @throws IllegalArgumentException if [text] is not a version of the session's text (programming error).
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
 * A range in a file: the target of navigation (definition, references, problems, outline).
 *
 * @property version the version of the text [range] was computed in: the version of the outcome the range came from —
 *   the request's `text.version` for tokens or the current parse, the `lastGoodParse` version for a declaration only
 *   known from the last good parse, `AnalyzedFile.snapshot.version` for another file of the modeling context, the
 *   diagnostics' snapshot for a problem; null if unknown.
 * @property lineage the [TextLineage] [version] counts in (the document's `text.lineage` for its own parses, the analyzed
 *   snapshot's for other files); null if unknown. Navigation maps the range into the open document's current text
 *   with `EditableText.mapRange(range, version, lineage)`, which answers null for another lineage (e.g. of a document
 *   that was closed and reopened since), a newer version or evicted history; navigation then clamps the range.
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

    /** A location of [range] in [snapshot] of the file [path] (version and lineage from [snapshot]). */
    public constructor(path: Path, range: TextRange, snapshot: TextSnapshot) :
        this(path, range, snapshot.version, snapshot.lineage)
}

/** Hover content for [range]: plain text, possibly several lines (no markup). */
public data class Hover(public val range: TextRange, public val text: String)

/** A matching bracket pair: the one-character ranges of the [open] and [close] bracket. */
public data class BracketMatch(public val open: TextRange, public val close: TextRange)
