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
 * @property text the text the request refers to (offsets are in its coordinates).
 * @property tokens tokens, normally of [text]'s version (see the [LanguageServices] contract).
 * @property parse the latest parse outcome, possibly of an older version, or null.
 */
public data class LanguageDocument(
    public val path: Path,
    public val language: KarpfenLanguage,
    public val text: TextSnapshot,
    public val tokens: TokenizeResult,
    public val parse: ParseOutcome?,
) {
    public companion object {
        /** The current state of [session] (text, tokens and parse as they are right now). */
        public fun of(session: DocumentSession): LanguageDocument =
            LanguageDocument(
                path = session.id.path,
                language = session.language,
                text = session.text.snapshot.value,
                tokens = session.tokens.value,
                parse = session.parse.value,
            )
    }
}

/** A range in a file: the target of navigation (definition, references, problems). */
public data class Location(public val path: Path, public val range: TextRange)

/** Hover content for [range]: plain text, possibly several lines (no markup). */
public data class Hover(public val range: TextRange, public val text: String)

/** A matching bracket pair: the one-character ranges of the [open] and [close] bracket. */
public data class BracketMatch(public val open: TextRange, public val close: TextRange)
