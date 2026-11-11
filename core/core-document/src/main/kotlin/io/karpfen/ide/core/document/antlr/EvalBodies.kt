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
package io.karpfen.ide.core.document.antlr

import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.Token

/**
 * An EVAL body of a KStates document: `EVAL` followed by `{`, up to the matching `}`. Its content is embedded Python
 * that the runtime executes.
 *
 * @property keyword the `EVAL` keyword.
 * @property open the opening `{`.
 * @property content the embedded Python between the braces. It runs to the end of the text when the body is not
 *   closed.
 * @property close the closing `}`, or null if the body is not closed.
 * @property firstToken index of the first token after [open] in the token list given to [EvalBodies.find].
 * @property endToken index of [close] in that list, or the list size when the body is not closed. It is the exclusive
 *   end of the body's tokens.
 */
internal data class EvalBody(
    val keyword: TextRange,
    val open: TextRange,
    val content: TextRange,
    val close: TextRange?,
    val firstToken: Int,
    val endToken: Int,
)

/** Classification of an [EmbeddedPiece]. */
internal enum class EmbeddedKind {
    /** Python code without a more specific kind. There is one piece per karpfen token. */
    CODE,

    /** A Python keyword of [EvalBodies.PYTHON_KEYWORDS]. */
    KEYWORD,

    /** A quoted string, which is a karpfen `STRING` or `SSTRING` token. */
    STRING,

    /** A number, which is a karpfen `NUMBER` token. */
    NUMBER,

    /** A Python `#` comment. It runs to the end of its line or of the body, without trailing blanks. */
    COMMENT,

    /**
     * A `//` comment. It is the karpfen lexer's `LINE_COMMENT`, which drops the rest of the line from the Python code.
     * The tokenizer emits it as a COMMENT token together with an `eval-double-slash` WARNING.
     */
    KARPFEN_COMMENT,

    /** A model-path reference such as `$(position)`, including `$(` and `)`. */
    PATH_REFERENCE,

    /** A macro reference `@name`, including the `@`. */
    MACRO_REFERENCE,
}

/**
 * One classified piece of an EVAL body.
 *
 * @property range the piece in the document.
 * @property kind what the piece is.
 * @property inner for [EmbeddedKind.PATH_REFERENCE], the path between the parentheses without surrounding blanks. It is
 *   an empty range after `$(` if there is no path. For [EmbeddedKind.MACRO_REFERENCE], the name after `@`. Null for
 *   every other kind.
 * @property name for [EmbeddedKind.PATH_REFERENCE], the path with blanks removed, such as `boundingBox->position`. For
 *   [EmbeddedKind.MACRO_REFERENCE], the macro name. Null for every other kind.
 * @property closed for [EmbeddedKind.PATH_REFERENCE], false if no `)` follows the path. True otherwise.
 */
internal data class EmbeddedPiece(
    val range: TextRange,
    val kind: EmbeddedKind,
    val inner: TextRange? = null,
    val name: String? = null,
    val closed: Boolean = true,
)

/**
 * The single implementation that finds embedded Python and classifies what is in it. Two consumers share it. The
 * KStates tokenizer builds the EMBEDDED_*, PATH_REFERENCE and MACRO_REFERENCE tokens and the `//` warnings from it.
 * The KStates tree walkers build the MODEL_PATH and MACRO references, their semantic spans and the EMBEDDED_CODE
 * folding from it. Highlighting, navigation and folding therefore agree on every input, broken ones included. Both
 * consumers use these lexical results, not the parse tree.
 *
 * A body is an `EVAL` token followed by an `LBRACE` token. Hidden tokens in between are ignored, as the parser ignores
 * them. Braces are balanced on default-channel `LBRACE` and `RBRACE` tokens, exactly as the dsl-tools parser counts
 * them. A `{` inside a Python `#` comment therefore counts, while braces inside strings or `//` comments do not. A body
 * without its closing brace runs to the end of the text. Bodies nested in a body are part of the outer body's content.
 *
 * The pieces of a body follow token order, and whitespace yields none. They are classified like this:
 * - A `#` symbol starts a COMMENT that runs to the end of its line or of the body. It swallows every token on the way,
 *   including `//`, strings and path references.
 * - A `LINE_COMMENT` token is a KARPFEN_COMMENT.
 * - `$` directly followed by `(` starts a PATH_REFERENCE. Its path consists of `[A-Za-z0-9_]`, blanks and `->`. A
 *   directly following `)` closes it. Otherwise it ends after the path and after every token that the path cut into.
 * - `@` directly followed by an identifier is a MACRO_REFERENCE.
 * - `STRING` and `SSTRING` tokens are STRING, and `NUMBER` tokens are NUMBER.
 * - Tokens whose text is in [PYTHON_KEYWORDS] are KEYWORD.
 * - Everything else is CODE.
 *
 * The pieces are sorted, do not overlap, lie inside the body's content and cover every non-whitespace character of it.
 */
internal object EvalBodies {

    /** The Python keywords highlighted as EMBEDDED_KEYWORD. Exactly these words are highlighted and no others. */
    val PYTHON_KEYWORDS: Set<String> = setOf(
        "def", "return", "for", "in", "if", "elif", "else", "while", "import", "from", "as", "and", "or", "not",
        "None", "True", "False", "lambda", "pass", "break", "continue", "endif", "endfor",
    )

    /** A macro name as it may follow `@`. */
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * The EVAL bodies of a KStates token list. The list holds the tokens of every channel in input order and may end
     * with EOF. Examples are [LexedText.tokens] and the tokens of the parser's `CommonTokenStream`. Never throws.
     */
    fun find(tokens: List<Token>, offsets: CodePointOffsets): List<EvalBody> {
        val bodies = ArrayList<EvalBody>()
        val textLength = offsets.toUtf16(offsets.codePointCount)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val openIndex = if (token.type == KstatesLexer.EVAL && isDefault(token)) nextDefault(tokens, i + 1) else -1
            if (openIndex < 0 || tokens[openIndex].type != KstatesLexer.LBRACE) {
                i++
                continue
            }
            val closeIndex = matchingClose(tokens, openIndex)
            val open = rangeOf(tokens[openIndex], offsets)
            val close = if (closeIndex >= 0) rangeOf(tokens[closeIndex], offsets) else null
            bodies += EvalBody(
                keyword = rangeOf(token, offsets),
                open = open,
                content = TextRange(open.end, maxOf(open.end, close?.start ?: textLength)),
                close = close,
                firstToken = openIndex + 1,
                endToken = if (closeIndex >= 0) closeIndex else tokens.size,
            )
            i = if (closeIndex >= 0) closeIndex + 1 else tokens.size
        }
        return bodies
    }

    /**
     * The pieces of [body]. The body must come from [find] over the same [tokens] of [text]. The tokens must include
     * every channel, because `//` comments are hidden tokens. Never throws.
     */
    fun scan(text: String, tokens: List<Token>, offsets: CodePointOffsets, body: EvalBody): List<EmbeddedPiece> {
        val pieces = ArrayList<EmbeddedPiece>()
        val limit = body.content.end
        var k = body.firstToken
        val end = minOf(body.endToken, tokens.size)
        while (k < end) {
            val token = tokens[k]
            if (token.type == Token.EOF) break
            val range = rangeOf(token, offsets)
            if (range.start >= limit) break
            val next: Token? = if (k + 1 < end && tokens[k + 1].type != Token.EOF) tokens[k + 1] else null
            val nextRange: TextRange? = next?.let { rangeOf(it, offsets) }
            when {
                token.type == KstatesLexer.WS -> k++
                token.type == KstatesLexer.LINE_COMMENT -> {
                    pieces += EmbeddedPiece(clip(range, limit), EmbeddedKind.KARPFEN_COMMENT)
                    k++
                }
                token.type == KstatesLexer.SYMBOL && token.text == KarpfenSyntax.PYTHON_LINE_COMMENT -> {
                    val lineEnd = lineEnd(text, range.start, limit)
                    val (nextIndex, swallowedEnd) = swallow(tokens, offsets, k + 1, end, trimEnd(text, range.start, lineEnd))
                    pieces += EmbeddedPiece(TextRange(range.start, minOf(swallowedEnd, limit)), EmbeddedKind.COMMENT)
                    k = nextIndex
                }
                token.type == KstatesLexer.SYMBOL && token.text == "$" && next != null && nextRange != null &&
                    next.type == KstatesLexer.LPAREN && nextRange.start == range.end -> {
                    val piece = pathReference(text, range.start, nextRange.end, limit)
                    val (nextIndex, swallowedEnd) = swallow(tokens, offsets, k + 2, end, piece.range.end)
                    pieces += piece.copy(range = TextRange(piece.range.start, minOf(swallowedEnd, limit)))
                    k = nextIndex
                }
                token.type == KstatesLexer.SYMBOL && token.text == "@" && next != null && nextRange != null &&
                    nextRange.start == range.end && IDENTIFIER.matches(next.text) -> {
                    pieces += EmbeddedPiece(
                        TextRange(range.start, nextRange.end),
                        EmbeddedKind.MACRO_REFERENCE,
                        inner = nextRange,
                        name = next.text,
                    )
                    k += 2
                }
                else -> {
                    pieces += EmbeddedPiece(clip(range, limit), classify(token))
                    k++
                }
            }
        }
        return pieces
    }

    /** [find] and [scan] for every body. */
    fun scanAll(text: String, tokens: List<Token>, offsets: CodePointOffsets): List<Pair<EvalBody, List<EmbeddedPiece>>> =
        find(tokens, offsets).map { body -> body to scan(text, tokens, offsets, body) }

    private fun classify(token: Token): EmbeddedKind = when {
        token.type == KstatesLexer.STRING || token.type == KstatesLexer.SSTRING -> EmbeddedKind.STRING
        token.type == KstatesLexer.NUMBER -> EmbeddedKind.NUMBER
        token.text in PYTHON_KEYWORDS -> EmbeddedKind.KEYWORD
        else -> EmbeddedKind.CODE
    }

    /**
     * The path reference whose `$` sits at [dollar] and whose path starts at [afterParen]. The documentation of
     * [EvalBodies] describes the path syntax.
     */
    private fun pathReference(text: String, dollar: Int, afterParen: Int, limit: Int): EmbeddedPiece {
        var p = afterParen
        while (p < limit) {
            val char = text[p]
            p += when {
                KarpfenSyntax.isIdentifierChar(char) || KarpfenSyntax.isBlank(char) -> 1
                KarpfenSyntax.isPathSeparatorAt(text, p, limit) -> KarpfenSyntax.MODEL_PATH_SEPARATOR.length
                else -> break
            }
        }
        val closed = p < limit && text[p] == ')'
        var innerEnd = p
        while (innerEnd > afterParen && KarpfenSyntax.isBlank(text[innerEnd - 1])) innerEnd--
        var innerStart = afterParen
        while (innerStart < innerEnd && KarpfenSyntax.isBlank(text[innerStart])) innerStart++
        val end = if (closed) p + 1 else maxOf(innerEnd, afterParen)
        return EmbeddedPiece(
            range = TextRange(dollar, end),
            kind = EmbeddedKind.PATH_REFERENCE,
            inner = TextRange(innerStart, innerEnd),
            name = KarpfenSyntax.normalizeModelPath(text.substring(innerStart, innerEnd)),
            closed = closed,
        )
    }

    /**
     * Skips the tokens from index [from] up to the exclusive index [end] that start before [until]. Returns the index
     * of the first token not skipped and the end of the swallowed region. That end grows over non-whitespace tokens
     * that cross [until].
     */
    private fun swallow(tokens: List<Token>, offsets: CodePointOffsets, from: Int, end: Int, until: Int): Pair<Int, Int> {
        var index = from
        var swallowedEnd = until
        while (index < end && tokens[index].type != Token.EOF) {
            val range = rangeOf(tokens[index], offsets)
            if (range.start >= swallowedEnd) break
            if (tokens[index].type != KstatesLexer.WS) swallowedEnd = maxOf(swallowedEnd, range.end)
            index++
        }
        return index to swallowedEnd
    }

    /** The offset of the first line break at or after [from], or [limit]. */
    private fun lineEnd(text: String, from: Int, limit: Int): Int {
        var i = from
        while (i < limit && text[i] != '\n' && text[i] != '\r') i++
        return i
    }

    /** [end] moved back over the blanks that precede it, but not before [start]. */
    private fun trimEnd(text: String, start: Int, end: Int): Int {
        var i = end
        while (i > start && KarpfenSyntax.isBlank(text[i - 1])) i--
        return i
    }

    /**
     * The index of the `}` that closes the `{` at [openIndex]. Only default-channel braces count. Returns -1 if the
     * body is not closed.
     */
    private fun matchingClose(tokens: List<Token>, openIndex: Int): Int {
        var depth = 1
        for (j in openIndex + 1 until tokens.size) {
            val token = tokens[j]
            if (!isDefault(token)) continue
            when (token.type) {
                KstatesLexer.LBRACE -> depth++
                KstatesLexer.RBRACE -> if (--depth == 0) return j
            }
        }
        return -1
    }

    /** The index of the first default-channel token at or after [from]. Returns -1 if that is EOF or there is none. */
    private fun nextDefault(tokens: List<Token>, from: Int): Int {
        for (j in from until tokens.size) {
            if (isDefault(tokens[j])) return if (tokens[j].type == Token.EOF) -1 else j
        }
        return -1
    }

    private fun isDefault(token: Token): Boolean = token.channel == Token.DEFAULT_CHANNEL

    /** The range of [token]. A conjured token without a position gets the empty range at 0. */
    private fun rangeOf(token: Token, offsets: CodePointOffsets): TextRange =
        offsets.range(token.startIndex, token.stopIndex) ?: TextRange.empty(0)

    private fun clip(range: TextRange, limit: Int): TextRange =
        TextRange(minOf(range.start, limit), minOf(range.end, limit))
}
