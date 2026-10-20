package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange

// The locators of the TokenCaretContextDetector: pure functions over a TokenView, one concern each.

/**
 * Finds the string literal around a caret: a STRING token the caret is strictly inside of (after the opening quote,
 * before the closing one), or an unterminated string — the last ERROR token on the caret's line that starts with a
 * quote (the KStates lexer emits a lone quote, the KMeta/KModel lexers drop the failed string, which the tokenizer
 * reports as ERROR tokens), running to the end of the line.
 */
internal object StringLocator {

    private const val QUOTES = "\"'"

    fun locate(view: TokenView, tokens: TokenizeResult, caret: Int): StringContext? {
        val before = tokens.tokenBefore(caret)
        if (before != null && before.kind == TokenKind.STRING && caret < before.range.end) {
            val raw = view.textOf(before)
            val closed = raw.length >= 2 && raw.last() == raw.first()
            val contentEnd = if (closed) before.range.end - 1 else before.range.end
            return StringContext(TextRange(before.range.start + 1, maxOf(before.range.start + 1, contentEnd)), closed)
        }
        val text = view.text
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val quote = tokens.tokensIn(TextRange(lineStart, caret)).lastOrNull { token ->
            token.kind == TokenKind.ERROR && token.range.start < caret && text[token.range.start] in QUOTES
        } ?: return null
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        return StringContext(TextRange(quote.range.start + 1, maxOf(quote.range.start + 1, lineEnd)), closed = false)
    }
}

/**
 * Finds the innermost unclosed call before a token index: scans backwards over balanced parentheses (skipping brace
 * blocks that close before it, e.g. an `EVAL { … }` argument) to an unmatched `(`, counting top-level commas; stops at
 * an unmatched `{`. The call's parents are found the same way from the token before its name.
 */
internal object CallLocator {

    private const val MAX_PARENTS = 8

    fun locate(view: TokenView, endIndex: Int): CallContext? = locate(view, endIndex, MAX_PARENTS)

    private fun locate(view: TokenView, endIndex: Int, parents: Int): CallContext? {
        val tokens = view.tokens
        var depth = 0
        var i = minOf(endIndex, tokens.size) - 1
        while (i >= 0) {
            when (view.punctuationOf(tokens[i])) {
                ')' -> depth++
                '(' -> if (depth == 0) return callAt(view, i, endIndex, parents) else depth--
                '}' -> i = matchingOpenBrace(view, i) ?: return null
                '{' -> return null
            }
            i--
        }
        return null
    }

    private fun callAt(view: TokenView, open: Int, endIndex: Int, parents: Int): CallContext {
        val tokens = view.tokens
        var nameIndex = open - 1
        while (nameIndex >= 0 && tokens[nameIndex].kind == TokenKind.COMMENT) nameIndex--
        val nameToken = tokens.getOrNull(nameIndex)?.takeIf { view.punctuationOf(it) == null }
        val arguments = argumentsBetween(view, open + 1, minOf(endIndex, tokens.size))
        return CallContext(
            name = nameToken?.let(view::textOf).orEmpty(),
            nameRange = nameToken?.range ?: TextRange.empty(tokens[open].range.start),
            argumentIndex = arguments.size,
            arguments = arguments,
            parent = if (parents > 0 && nameToken != null) locate(view, nameIndex, parents - 1) else null,
        )
    }

    /** The complete top-level arguments in `[from, to)`: the content of a single-string argument, else null. */
    private fun argumentsBetween(view: TokenView, from: Int, to: Int): List<String?> {
        val arguments = ArrayList<String?>()
        val current = ArrayList<SyntaxToken>()
        var depth = 0
        for (i in from until to) {
            val token = view.tokens[i]
            if (token.kind == TokenKind.COMMENT) continue
            when (view.punctuationOf(token)) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                ',' -> if (depth == 0) {
                    arguments += current.singleOrNull()?.takeIf { it.kind == TokenKind.STRING }?.let { view.contextToken(it).text }
                    current.clear()
                    continue
                }
            }
            current += token
        }
        return arguments
    }

    /** Index of the `{` matching the `}` at [close], scanning backwards; null if there is none. */
    private fun matchingOpenBrace(view: TokenView, close: Int): Int? {
        var depth = 0
        for (i in close downTo 0) {
            when (view.punctuationOf(view.tokens[i])) {
                '}' -> depth++
                '{' -> if (--depth == 0) return i
            }
        }
        return null
    }
}

/**
 * Finds the brace blocks around a caret: one pass over the brace tokens before the caret with a stack of open blocks,
 * then a pass after it for their closing braces. A block contains the caret from after its `{` through before its `}`.
 */
internal object BlockLocator {

    fun locate(view: TokenView, caret: Int): List<BlockFrame> {
        val tokens = view.tokens
        val open = ArrayList<Int>()
        var i = 0
        while (i < tokens.size && tokens[i].range.end <= caret) {
            when (view.punctuationOf(tokens[i])) {
                '{' -> open += i
                '}' -> open.removeLastOrNull()
            }
            i++
        }
        val closes = arrayOfNulls<TextRange>(open.size)
        var unclosed = open.size
        var depth = 0
        while (i < tokens.size && unclosed > 0) {
            when (view.punctuationOf(tokens[i])) {
                '{' -> depth++
                '}' -> if (depth > 0) depth-- else closes[--unclosed] = tokens[i].range
            }
            i++
        }
        return open.mapIndexed { level, index ->
            val start = tokens[index].range.start
            BlockFrame(view.statementBefore(index), TextRange(start, maxOf(start, closes[level]?.end ?: view.text.length)))
        }
    }
}

/**
 * Describes a caret inside an EVAL body (found with `TokenizeResult.embeddedRegionAt`): the path before the caret when
 * it is inside a PATH_REFERENCE token (after `$(`, before a closing `)`), the macro name before the caret when it is in a
 * MACRO_REFERENCE token or right after a lone `@`.
 */
internal object EmbeddedLocator {

    fun locate(view: TokenView, tokens: TokenizeResult, body: TextRange, caret: Int): EmbeddedContext {
        val candidates = listOfNotNull(tokens.tokenAt(caret), tokens.tokenBefore(caret)).distinct()
        val path = candidates.firstOrNull { it.kind == TokenKind.PATH_REFERENCE && isInsidePath(view, it, caret) }
        val macro = candidates.firstOrNull { token ->
            (token.kind == TokenKind.MACRO_REFERENCE && caret > token.range.start && caret <= token.range.end) ||
                (token.kind == TokenKind.EMBEDDED_CODE && token.range.end == caret && view.textOf(token) == "@")
        }
        return EmbeddedContext(
            bodyRange = body,
            pathPrefix = path?.let { view.text.substring(it.range.start + 2, caret).filterNot(::isBlank) },
            macroPrefix = macro?.let { view.text.substring(it.range.start + 1, caret) },
        )
    }

    private fun isInsidePath(view: TokenView, token: SyntaxToken, caret: Int): Boolean {
        val closed = token.range.length > 2 && view.text.getOrNull(token.range.end - 1) == ')'
        val end = if (closed) token.range.end - 1 else token.range.end
        return caret >= token.range.start + 2 && caret <= end
    }

    private fun isBlank(char: Char): Boolean = char == ' ' || char == '\t'
}
