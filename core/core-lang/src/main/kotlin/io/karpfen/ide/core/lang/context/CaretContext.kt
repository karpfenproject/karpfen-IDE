package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.text.TextRange

/**
 * Shared mechanic "context detection around the caret" (spec §5): what surrounds an offset, determined from the
 * tokens (robust on broken text) and refined with the partial parse when available. Language-neutral; the
 * per-language completion providers interpret it. Owned by WP-LANG, which may refine these types.
 */
public fun interface CaretContextDetector {
    /** The context of [offset] in [document]. Never throws. */
    public fun detect(document: LanguageDocument, offset: Int): CaretContext
}

/** The default token-based [CaretContextDetector]. */
public fun CaretContextDetector(): CaretContextDetector = TokenCaretContextDetector()

/**
 * The surroundings of a caret.
 *
 * @property offset the caret offset.
 * @property replaceRange the identifier, keyword or string content under/before the caret (empty at the caret if
 *   there is none): what a completion item replaces.
 * @property string set when the caret is inside a string literal (also an unterminated one).
 * @property call the innermost unclosed call around the caret, e.g. `prop(` or `MACRO(`.
 * @property blocks the enclosing brace blocks, outermost first.
 * @property embedded set exactly when `document.tokens.embeddedRegionAt(offset)` finds an EVAL body (the tokenizer's
 *   scanner is the only definition of "inside embedded Python"; the detector never re-derives EVAL bodies from tokens).
 */
public data class CaretContext(
    public val offset: Int,
    public val replaceRange: TextRange,
    public val string: StringContext?,
    public val call: CallContext?,
    public val blocks: List<BlockFrame>,
    public val embedded: EmbeddedContext?,
)

/** A string literal around the caret: [contentRange] excludes the quotes; [closed] is false for an unterminated one. */
public data class StringContext(public val contentRange: TextRange, public val closed: Boolean)

/**
 * A call around the caret: [name] is the token before `(` (e.g. `prop`, `has`, `EVENT`, `MACRO`, `TAKES`),
 * [argumentIndex] the zero-based index of the argument the caret is in (counted by top-level commas).
 */
public data class CallContext(public val name: String, public val nameRange: TextRange, public val argumentIndex: Int)

/**
 * An enclosing `{ … }` block: [header] are the tokens' texts that introduce it (e.g. `["STATE", "s1"]`,
 * `["type", "Vector", "A vector"]`, `["make", "object", "k", ":", "T"]`, `["EVAL"]`), without quotes; [range] spans
 * from the opening brace to the closing one (or to the end of the text when unclosed).
 */
public data class BlockFrame(public val header: List<String>, public val range: TextRange)

/**
 * The caret is inside an EVAL body: [bodyRange] is `TokenizeResult.embeddedRegionAt(offset)`. [pathPrefix] is the text
 * between `$(` and the caret when the caret is inside a `$( … )` reference (e.g. `boundingBox->pos`, from the
 * PATH_REFERENCE token); [macroPrefix] the text after `@` when it is in a macro reference (MACRO_REFERENCE token).
 */
public data class EmbeddedContext(
    public val bodyRange: TextRange,
    public val pathPrefix: String?,
    public val macroPrefix: String?,
)

/** Token-based [CaretContextDetector]. */
internal class TokenCaretContextDetector : CaretContextDetector {
    override fun detect(document: LanguageDocument, offset: Int): CaretContext =
        TODO("WP-LANG: detect the caret context from tokens (${document.tokens.tokens.size} tokens) and the partial parse")
}
