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
 * The shared mechanic of context detection around the caret. It finds what surrounds an offset from the tokens. The
 * tokens always belong to the request's text and work on broken text. The detector does not depend on a language and
 * is shared by all languages instead of being copied for each one. The slot detectors of each language interpret its
 * result. There the parse outcome adds knowledge by name, following the staleness rule on [LanguageDocument].
 */
public fun interface CaretContextDetector {
    /** The context of [offset] in [document]. The offset is clamped to the text. It never throws. */
    public fun detect(document: LanguageDocument, offset: Int): CaretContext
}

/** The default [CaretContextDetector], which works on tokens. */
public fun CaretContextDetector(): CaretContextDetector = TokenCaretContextDetector()

/**
 * The surroundings of a caret.
 *
 * @property offset the caret offset, clamped to the text.
 * @property replaceRange what a completion item replaces. Inside a string it is the content of the string around the
 *   caret. For an unterminated string it runs from the first character of the content to the end of the word at the
 *   caret. Outside a string it is [wordRange].
 * @property wordRange the identifier characters `A-Z a-z 0-9 _` that touch the caret. If there are none, it is empty
 *   at the caret. An example is the path segment `pos` in `"boundingBox->pos"`.
 * @property string set when the caret is inside a string literal, also an unterminated one.
 * @property call the innermost unclosed call around the caret, or around the string the caret is in, such as `prop(`
 *   or `MACRO(`.
 * @property blocks the enclosing brace blocks, outermost first.
 * @property statement the tokens before the word at the caret, or before the string the caret is in, collected by the
 *   rule of [BlockFrame.header]. For example, it is `TRANSITION a ->` before `"b"` of `TRANSITION "a" -> "b"`.
 * @property embedded set exactly when `document.tokens.embeddedRegionAt(offset)` finds an EVAL body. The scanner of the
 *   tokenizer is the only definition of "inside embedded Python". The detector never derives EVAL bodies from tokens
 *   again.
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

/**
 * A string literal around the caret. [contentRange] excludes the quotes. [closed] is false for an unterminated string.
 */
public data class StringContext(public val contentRange: TextRange, public val closed: Boolean)

/**
 * A call around the caret. [name] is the token before `(`, such as `prop`, `has`, `EVENT`, `MACRO`, `TAKES`, or
 * `SCOPE` for `IF IN SCOPE(`. [argumentIndex] is the zero-based index of the argument the caret is in, counted by
 * top-level commas.
 *
 * @property arguments the arguments before the one the caret is in. Each entry is the content of an argument that is
 *   exactly one string literal, or null for any other argument. For the relation argument of `SETOBJ("self", "rel…`
 *   it is `["self"]`.
 * @property parent the next enclosing unclosed call, or null. For `list(` in `prop("k", list("…` it is `prop(`.
 */
public data class CallContext(
    public val name: String,
    public val nameRange: TextRange,
    public val argumentIndex: Int,
    public val arguments: List<String?> = emptyList(),
    public val parent: CallContext? = null,
)

/**
 * A token as the caret context sees it. [text] is the text of the token, without the quotes for a string literal.
 */
public data class ContextToken(public val text: String, public val kind: TokenKind, public val range: TextRange)

/**
 * An enclosing `{ ... }` block.
 *
 * @property header the tokens before its `{`, without comments, back to the previous brace. When the block is an
 *   argument of an unclosed call, as in `SET("p", EVAL {`, the header goes back to the last top-level comma of the call
 *   or to its `(`. It holds at most 64 tokens. Examples are `STATE s1`, `type Vector A vector`, `EVAL` and
 *   `TRANSITION a -> b`. In languages without statement separators the earlier statements of the block stay in
 *   front, as in `SET ( a , b ) IF IN SCOPE ( p )` or `prop ( k ) -> v has ( r ) -> make object k : T`. So each
 *   language finds the keyword that introduces the block itself, as the block tracker of the KStates tokenizer does.
 * @property range the span from the opening brace to the closing one, or to the end of the text when the block is
 *   unclosed.
 */
public data class BlockFrame(public val header: List<ContextToken>, public val range: TextRange)

/**
 * The caret is inside an EVAL body. [bodyRange] is `TokenizeResult.embeddedRegionAt(offset)`. [pathPrefix] is the
 * text between `$(` and the caret when the caret is inside a `$( ... )` reference, such as `boundingBox->pos`. It comes
 * from the PATH_REFERENCE token, with blanks removed. [macroPrefix] is the text after `@` when the caret is in a macro
 * reference. That is a MACRO_REFERENCE token, or a lone `@` before the caret.
 */
public data class EmbeddedContext(
    public val bodyRange: TextRange,
    public val pathPrefix: String?,
    public val macroPrefix: String?,
)

/**
 * The [CaretContextDetector] that works on tokens. It coordinates the locators of strings, calls, blocks, statements
 * and embedded references over one [TokenView] of the document.
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
     * The offset before which the call and the statement around the caret end. It is the opening quote of the
     * [string] the caret is in, or else the start of the [word] being typed.
     */
    private fun anchorOf(string: StringContext?, word: TextRange): Int = string?.let { it.contentRange.start - 1 } ?: word.start

    /**
     * The content of a closed [string]. For an unterminated one it is the content up to the end of the [word] at the
     * caret.
     */
    private fun replaceRangeIn(string: StringContext, word: TextRange): TextRange =
        if (string.closed) string.contentRange else TextRange(string.contentRange.start, maxOf(string.contentRange.start, word.end))
}
