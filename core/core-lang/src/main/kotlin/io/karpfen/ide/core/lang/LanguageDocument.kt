package io.karpfen.ide.core.lang

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path

/**
 * A document as seen by [LanguageServices] (spec §5): immutable inputs only, so services can run off the UI thread.
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
         * The current state of [session]: its text, the tokens of exactly that text (tokenized synchronously with the
         * session's tokenizer when the session's tokens lag behind, e.g. right after an edit), the latest parse and the
         * last good parse. The result is always [isConsistent].
         */
        public fun of(session: DocumentSession): LanguageDocument {
            val text = session.text.snapshot.value
            val tokens = session.tokens.value.takeIf { it.version == text.version } ?: session.support.tokenizer.tokenize(text)
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
 * A range in a file: the target of navigation (definition, references, problems).
 *
 * @property version the version of the text snapshot [range] was computed in: the request's `text.version` for the
 *   request's own document, `AnalyzedFile.snapshot.version` for another file of the modeling context; null if
 *   unknown. Navigation maps a versioned range into the open document's current text with `EditableText.mapOffset`
 *   when that is possible (version not newer than the current one, history not evicted) and clamps it otherwise.
 */
public data class Location(public val path: Path, public val range: TextRange, public val version: Long? = null)

/** Hover content for [range]: plain text, possibly several lines (no markup). */
public data class Hover(public val range: TextRange, public val text: String)

/** A matching bracket pair: the one-character ranges of the [open] and [close] bracket. */
public data class BracketMatch(public val open: TextRange, public val close: TextRange)
