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
package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.text.TextRange

/**
 * The shared mechanic of context detection around the caret: what surrounds an offset, determined from the tokens,
 * which always belong to the request's text and are robust on broken text. Language-neutral and shared by all
 * languages instead of being copied per language; the per-language slot detectors interpret it (the parse outcome
 * contributes name-based knowledge there, see the staleness rule of [LanguageDocument]).
 */
public fun interface CaretContextDetector {
    /** The context of [offset] (clamped to the text) in [document]. Never throws. */
    public fun detect(document: LanguageDocument, offset: Int): CaretContext
}

/** The default token-based [CaretContextDetector]. */
public fun CaretContextDetector(): CaretContextDetector = TokenCaretContextDetector()

/**
 * The surroundings of a caret.
 *
 * @property offset the caret offset (clamped to the text).
 * @property replaceRange what a completion item replaces: the content of the string around the caret (for an
 *   unterminated string from its first character to the end of the word at the caret), else [wordRange].
 * @property wordRange the identifier characters (`A-Z a-z 0-9 _`) touching the caret, empty at the caret if there are
 *   none (e.g. the path segment `pos` in `"boundingBox->pos"`).
 * @property string set when the caret is inside a string literal (also an unterminated one).
 * @property call the innermost unclosed call around the caret (or around the string the caret is in), e.g. `prop(` or
 *   `MACRO(`.
 * @property blocks the enclosing brace blocks, outermost first.
 * @property statement the tokens before the word at the caret (or before the string the caret is in), by the rule of
 *   [BlockFrame.header]; e.g. `TRANSITION a ->` before `"b"` of `TRANSITION "a" -> "b"`.
 * @property embedded set exactly when `document.tokens.embeddedRegionAt(offset)` finds an EVAL body (the tokenizer's
 *   scanner is the only definition of "inside embedded Python"; the detector never re-derives EVAL bodies from tokens).
 * @property inComment true if the caret is inside or at the end of a `//` comment.
 */
public data class CaretContext(
    public val offset: Int,
    public val replaceRange: TextRange,
    public val string: StringContext?,
    public val call: CallContext?,
    public val blocks: List<BlockFrame>,
    public val embedded: EmbeddedContext?,
    public val wordRange: TextRange = replaceRange,
    public val statement: List<ContextToken> = emptyList(),
    public val inComment: Boolean = false,
) {
    /** The innermost enclosing block, or null at top level. */
    public val innermostBlock: BlockFrame? get() = blocks.lastOrNull()
}

/** A string literal around the caret: [contentRange] excludes the quotes; [closed] is false for an unterminated one. */
public data class StringContext(public val contentRange: TextRange, public val closed: Boolean)

/**
 * A call around the caret: [name] is the token before `(` (e.g. `prop`, `has`, `EVENT`, `MACRO`, `TAKES`, `SCOPE` for
 * `IF IN SCOPE(`), [argumentIndex] the zero-based index of the argument the caret is in (counted by top-level commas).
 *
 * @property arguments the arguments before the caret's one: the content of an argument that is exactly one string
 *   literal, else null (e.g. `["self"]` for the relation argument of `SETOBJ("self", "rel…`).
 * @property parent the next enclosing unclosed call (e.g. `prop(` around `list(` in `prop("k", list("…`), or null.
 */
public data class CallContext(
    public val name: String,
    public val nameRange: TextRange,
    public val argumentIndex: Int,
    public val arguments: List<String?> = emptyList(),
    public val parent: CallContext? = null,
)

/**
 * A token as the caret context sees it: [text] is the token's text, without the quotes for a string literal.
 */
public data class ContextToken(public val text: String, public val kind: TokenKind, public val range: TextRange)

/**
 * An enclosing `{ … }` block.
 *
 * @property header the tokens before its `{` (comments excluded) back to the previous brace, or — when the block is an
 *   argument of an unclosed call, as in `SET("p", EVAL {` — back to the call's last top-level comma or its `(`; at most 64
 *   tokens. E.g. `STATE s1`, `type Vector A vector`, `EVAL`, `TRANSITION a -> b`.
 *   Languages without statement separators keep the block's earlier statements in front (`SET ( a , b ) IF IN SCOPE (
 *   p )`, `prop ( k ) -> v has ( r ) -> make object k : T`), so each language finds the keyword that introduces the
 *   block itself (as the KStates tokenizer's block tracker does).
 * @property range spans from the opening brace to the closing one (or to the end of the text when unclosed).
 */
public data class BlockFrame(public val header: List<ContextToken>, public val range: TextRange)

/**
 * The caret is inside an EVAL body: [bodyRange] is `TokenizeResult.embeddedRegionAt(offset)`. [pathPrefix] is the text
 * between `$(` and the caret when the caret is inside a `$( … )` reference (e.g. `boundingBox->pos`, from the
 * PATH_REFERENCE token; blanks removed); [macroPrefix] the text after `@` when it is in a macro reference
 * (MACRO_REFERENCE token, or a lone `@` before the caret).
 */
public data class EmbeddedContext(
    public val bodyRange: TextRange,
    public val pathPrefix: String?,
    public val macroPrefix: String?,
)

/**
 * Token-based [CaretContextDetector]: coordinates the locators of strings, calls, blocks, statements and embedded
 * references over one [TokenView] of the document.
 */
internal class TokenCaretContextDetector : CaretContextDetector {

    override fun detect(document: LanguageDocument, offset: Int): CaretContext {
        val view = TokenView(document.text.text, document.tokens.tokens)
        val caret = offset.coerceIn(0, view.text.length)
        val word = view.wordRange(caret)
        val inComment = document.tokens.tokenBefore(caret)?.kind == TokenKind.COMMENT
        val embedded = document.tokens.embeddedRegionAt(caret)?.let { EmbeddedLocator.locate(view, document.tokens, it, caret) }
        val string = if (embedded == null && !inComment) StringLocator.locate(view, document.tokens, caret) else null
        val anchorIndex = view.firstIndexStartingAtOrAfter(anchorOf(string, word))
        return CaretContext(
            offset = caret,
            replaceRange = string?.let { replaceRangeIn(it, word) } ?: word,
            string = string,
            call = if (embedded == null) CallLocator.locate(view, anchorIndex) else null,
            blocks = BlockLocator.locate(view, caret),
            embedded = embedded,
            wordRange = word,
            statement = view.statementBefore(anchorIndex),
            inComment = inComment,
        )
    }

    /**
     * The offset the call and the statement around the caret end before: the opening quote of the [string] the caret
     * is in, else the start of the [word] being typed.
     */
    private fun anchorOf(string: StringContext?, word: TextRange): Int = string?.let { it.contentRange.start - 1 } ?: word.start

    /**
     * The content of a closed [string]; for an unterminated one, its content up to the end of the [word] at the caret.
     */
    private fun replaceRangeIn(string: StringContext, word: TextRange): TextRange =
        if (string.closed) string.contentRange else TextRange(string.contentRange.start, maxOf(string.contentRange.start, word.end))
}
