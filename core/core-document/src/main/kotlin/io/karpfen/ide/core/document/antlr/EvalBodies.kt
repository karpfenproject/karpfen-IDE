package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange
import kstates.KstatesLexer
import org.antlr.v4.runtime.Token

/**
 * An EVAL body of a KStates document: `EVAL` followed by `{`, up to the matching `}`. Its content is embedded Python
 * that the runtime executes.
 *
 * @property keyword the `EVAL` keyword.
 * @property open the opening `{`.
 * @property content the embedded Python between the braces (to the end of the text when unclosed).
 * @property close the closing `}`, or null if the body is not closed.
 * @property firstToken index (into the token list given to [EvalBodies.find]) of the first token after [open].
 * @property endToken index of [close] in that list, or its size when unclosed (exclusive end of the body's tokens).
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
    /** Python code without a more specific kind (one piece per karpfen token). */
    CODE,

    /** A Python keyword of [EvalBodies.PYTHON_KEYWORDS]. */
    KEYWORD,

    /** A quoted string (a karpfen `STRING` or `SSTRING` token). */
    STRING,

    /** A number (a karpfen `NUMBER` token). */
    NUMBER,

    /** A Python `#` comment up to the end of its line (trailing blanks excluded) or the end of the body. */
    COMMENT,

    /**
     * A `//` comment: the karpfen lexer's `LINE_COMMENT`, which drops the rest of the line from the Python code. The
     * tokenizer emits it as a COMMENT token plus an `eval-double-slash` WARNING.
     */
    KARPFEN_COMMENT,

    /** A model-path reference `$( … )`, including `$(` and `)`. */
    PATH_REFERENCE,

    /** A macro reference `@name`, including the `@`. */
    MACRO_REFERENCE,
}

/**
 * One classified piece of an EVAL body.
 *
 * @property range the piece in the document.
 * @property kind what the piece is.
 * @property inner for [EmbeddedKind.PATH_REFERENCE] the path between the parentheses without surrounding blanks (an
 *   empty range after `$(` if there is no path); for [EmbeddedKind.MACRO_REFERENCE] the name after `@`; else null.
 * @property name for [EmbeddedKind.PATH_REFERENCE] the path with blanks removed (e.g. `boundingBox->position`); for
 *   [EmbeddedKind.MACRO_REFERENCE] the macro name; else null.
 * @property closed for [EmbeddedKind.PATH_REFERENCE] false if no `)` follows the path; true otherwise.
 */
internal data class EmbeddedPiece(
    val range: TextRange,
    val kind: EmbeddedKind,
    val inner: TextRange? = null,
    val name: String? = null,
    val closed: Boolean = true,
)

/**
 * The single implementation of "where is embedded Python and what is in it", shared by the KStates tokenizer (the
 * EMBEDDED_*, PATH_REFERENCE and MACRO_REFERENCE tokens and the `//` warnings) and the KStates tree walkers (MODEL_PATH
 * and MACRO references, their semantic spans, EMBEDDED_CODE folding), so highlighting, navigation and folding agree on
 * every input, broken ones included. Both consumers use these lexical results, not the parse tree.
 *
 * Rules:
 * - Bodies: an `EVAL` token followed by an `LBRACE` token (hidden tokens in between are ignored, as by the parser);
 *   braces are balanced on default-channel `LBRACE`/`RBRACE` tokens exactly as the dsl-tools parser counts them, so a
 *   `{` inside a Python `#` comment counts, while braces inside strings or `//` comments do not. A body without its
 *   closing brace runs to the end of the text. Bodies nested in a body are part of the outer body's content.
 * - Pieces (in token order; whitespace yields none): a `#` symbol starts a COMMENT to the end of its line (or the body)
 *   and swallows every token on the way (`//`, strings, `$(…)` included); a `LINE_COMMENT` token is a
 *   KARPFEN_COMMENT; `$` directly followed by `(` starts a PATH_REFERENCE whose path consists of `[A-Za-z0-9_]`,
 *   blanks and `->`; it is closed by a directly following `)`, otherwise it ends after the path (and after every token
 *   the path cut into); `@` directly followed by an identifier is a MACRO_REFERENCE; `STRING`/`SSTRING` tokens are
 *   STRING; `NUMBER` tokens are NUMBER; tokens whose text is in [PYTHON_KEYWORDS] are KEYWORD; everything else is CODE.
 * - Invariants: pieces are sorted, do not overlap, lie inside the body's content and cover every non-whitespace
 *   character of it.
 */
internal object EvalBodies {

    /** The Python keywords highlighted as EMBEDDED_KEYWORD: exactly this list, no other word. */
    val PYTHON_KEYWORDS: Set<String> = setOf(
        "def", "return", "for", "in", "if", "elif", "else", "while", "import", "from", "as", "and", "or", "not",
        "None", "True", "False", "lambda", "pass", "break", "continue", "endif", "endfor",
    )

    /** A macro name as it may follow `@`. */
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * The EVAL bodies of a KStates token list (every channel, in input order, EOF allowed), e.g. [LexedText.tokens] or
     * the tokens of the parser's `CommonTokenStream`. Never throws.
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
     * The pieces of [body], which must come from [find] over the same [tokens] (every channel: `//` comments are
     * hidden tokens) of [text]. Never throws.
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
                token.type == KstatesLexer.SYMBOL && token.text == "#" -> {
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

    /** `$(` at [dollar] with the path starting at [afterParen]; see the class documentation for the path syntax. */
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
     * Skips the tokens from [from] (exclusive [end]) that start before [until]; returns the index of the first token
     * not skipped and the end of the swallowed region, extended over non-whitespace tokens that cross [until].
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
     * The index of the `}` that closes the `{` at [openIndex], counting default-channel braces only; -1 if the body is
     * not closed.
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

    /** The index of the first default-channel token at or after [from]; -1 if that is EOF or there is none. */
    private fun nextDefault(tokens: List<Token>, from: Int): Int {
        for (j in from until tokens.size) {
            if (isDefault(tokens[j])) return if (tokens[j].type == Token.EOF) -1 else j
        }
        return -1
    }

    private fun isDefault(token: Token): Boolean = token.channel == Token.DEFAULT_CHANNEL

    /** The range of [token]; the empty range at 0 for a conjured token without a position. */
    private fun rangeOf(token: Token, offsets: CodePointOffsets): TextRange =
        offsets.range(token.startIndex, token.stopIndex) ?: TextRange.empty(0)

    private fun clip(range: TextRange, limit: Int): TextRange =
        TextRange(minOf(range.start, limit), minOf(range.end, limit))
}
