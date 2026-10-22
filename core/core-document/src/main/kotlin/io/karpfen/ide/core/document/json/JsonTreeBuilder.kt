package io.karpfen.ide.core.document.json

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange

/**
 * Builds the [JsonNode] tree of a token list by recursive descent and recovers from every error, so a broken text still
 * yields the tree of everything around the error; one instance per text, used once.
 *
 * Recovery rules:
 * - a missing `,` between members or items is reported at the member or item that follows, which is then read;
 * - a key without `:` is reported at the key; if a value follows that does not start the next member (a string
 *   followed by `:`), it is read as the key's value;
 * - a missing value is reported at the `:` and leaves [JsonNode.Member.value] null; an INVALID token in the place of a
 *   value or member is consumed silently, because the lexer reported it already, and an unquoted key with its value is
 *   skipped the same way;
 * - a closing bracket of an enclosing container ends every container opened since, each one reported once as not
 *   closed; a closing bracket that closes nothing is reported and skipped;
 * - the end of the text ends every open container and missing part with one error at the end of the last token;
 * - content after the root value is one error, and the root must be a value;
 * - containers nested deeper than [MAX_DEPTH] are skipped as one error, so the recursion stays far from the stack limit.
 *
 * Errors are PARSER diagnostics with the code `syntax-error`.
 */
internal class JsonTreeBuilder(private val text: String, private val tokens: List<JsonToken>) {

    private var index = 0

    /** The end of the last consumed token. */
    private var lastEnd = 0

    /** Set once the end of the text was reported, so it is reported only once. */
    private var endReported = false

    private val found = ArrayList<Diagnostic>()

    /** The PARSER errors found so far, in the order they were found. */
    val diagnostics: List<Diagnostic> get() = found

    /** The root value, or null if the text holds none. */
    fun document(): JsonNode? {
        val first = peek()
        val root = when {
            first == null -> null.also { atEnd("Expected a JSON value.") }
            first.kind.isValueStart -> value(depth = 0, closers = emptySet())
            else -> null.also { if (first.kind != JsonTokenKind.INVALID) report(first.range, "Expected a JSON value.") }
        }
        tokens.drop(index).firstOrNull { it.kind != JsonTokenKind.INVALID && it !== first }?.let { extra ->
            report(extra.range, "Unexpected '${textOf(extra)}' after the end of the JSON value.")
        }
        return root
    }

    /** The value starting at the current token, which must start one. */
    private fun value(depth: Int, closers: Set<JsonTokenKind>): JsonNode {
        val token = next()
        return when (token.kind) {
            JsonTokenKind.BEGIN_OBJECT -> objectNode(token, depth, closers)
            JsonTokenKind.BEGIN_ARRAY -> arrayNode(token, depth, closers)
            JsonTokenKind.STRING -> stringNode(token)
            JsonTokenKind.NUMBER -> JsonNode.NumberNode(token.range, textOf(token))
            JsonTokenKind.TRUE -> JsonNode.LiteralNode(token.range, JsonLiteral.TRUE)
            JsonTokenKind.FALSE -> JsonNode.LiteralNode(token.range, JsonLiteral.FALSE)
            JsonTokenKind.NULL -> JsonNode.LiteralNode(token.range, JsonLiteral.NULL)
            else -> error("value() called at ${token.kind}, which starts no value")
        }
    }

    /** The object opened by [open]; [closers] are the closing brackets of the enclosing containers. */
    private fun objectNode(open: JsonToken, depth: Int, closers: Set<JsonTokenKind>): JsonNode {
        if (depth >= MAX_DEPTH) return tooDeep(open)
        val members = ArrayList<JsonNode.Member>()
        val inner = closers + JsonTokenKind.END_OBJECT
        var expectMember = true
        var comma: JsonToken? = null
        while (true) {
            val token = peek()
            when {
                token == null -> {
                    atEnd("The object is not closed; add '}'.")
                    return JsonNode.ObjectNode(TextRange(open.range.start, lastEnd), members, isClosed = false)
                }
                token.kind == JsonTokenKind.END_OBJECT -> {
                    next()
                    if (comma != null && members.isNotEmpty()) report(comma.range, "Remove the ',' before '}'; JSON allows no trailing comma.")
                    return JsonNode.ObjectNode(TextRange(open.range.start, token.range.end), members, isClosed = true)
                }
                token.kind in closers -> {
                    report(token.range, "Expected '}' to close the object before '${textOf(token)}'.")
                    return JsonNode.ObjectNode(TextRange(open.range.start, lastEnd), members, isClosed = false)
                }
                token.kind == JsonTokenKind.STRING -> {
                    if (!expectMember) report(token.range, "Expected ',' before this member.")
                    members += member(depth, inner)
                    expectMember = false
                    comma = null
                }
                token.kind == JsonTokenKind.VALUE_SEPARATOR -> {
                    next()
                    if (expectMember) report(token.range, if (members.isEmpty()) "Expected a member before ','." else "Remove the extra ','.")
                    expectMember = true
                    comma = token
                }
                token.kind == JsonTokenKind.INVALID -> {
                    skipUnquotedMember(depth, inner)
                    expectMember = false
                    comma = null
                }
                else -> {
                    report(token.range, "Expected a key in double quotes.")
                    skip(depth, inner)
                    expectMember = false
                    comma = null
                }
            }
        }
    }

    /** The member whose key is the current string token. */
    private fun member(depth: Int, closers: Set<JsonTokenKind>): JsonNode.Member {
        val key = stringNode(next())
        val separator = peek()
        if (separator == null) {
            atEnd("Expected ':' after the key.")
            return JsonNode.Member(key, colon = null, value = null)
        }
        if (separator.kind != JsonTokenKind.NAME_SEPARATOR) {
            report(key.range, "Expected ':' after the key.")
            val value = if (separator.kind.isValueStart && !startsMember(index)) value(depth + 1, closers) else null
            return JsonNode.Member(key, colon = null, value = value)
        }
        next()
        val colon = separator.range
        val start = peek()
        return when {
            start == null -> JsonNode.Member(key, colon, null).also { atEnd("Expected a value after ':'.") }
            start.kind.isValueStart && !startsMember(index) -> JsonNode.Member(key, colon, value(depth + 1, closers))
            start.kind == JsonTokenKind.INVALID -> JsonNode.Member(key, colon, null).also { next() }
            else -> JsonNode.Member(key, colon, null).also { report(colon, "Expected a value after ':'.") }
        }
    }

    /** The array opened by [open]; [closers] are the closing brackets of the enclosing containers. */
    private fun arrayNode(open: JsonToken, depth: Int, closers: Set<JsonTokenKind>): JsonNode {
        if (depth >= MAX_DEPTH) return tooDeep(open)
        val items = ArrayList<JsonNode>()
        val inner = closers + JsonTokenKind.END_ARRAY
        var expectItem = true
        var comma: JsonToken? = null
        while (true) {
            val token = peek()
            when {
                token == null -> {
                    atEnd("The list is not closed; add ']'.")
                    return JsonNode.ArrayNode(TextRange(open.range.start, lastEnd), items, isClosed = false)
                }
                token.kind == JsonTokenKind.END_ARRAY -> {
                    next()
                    if (comma != null && items.isNotEmpty()) report(comma.range, "Remove the ',' before ']'; JSON allows no trailing comma.")
                    return JsonNode.ArrayNode(TextRange(open.range.start, token.range.end), items, isClosed = true)
                }
                token.kind in closers -> {
                    report(token.range, "Expected ']' to close the list before '${textOf(token)}'.")
                    return JsonNode.ArrayNode(TextRange(open.range.start, lastEnd), items, isClosed = false)
                }
                token.kind == JsonTokenKind.VALUE_SEPARATOR -> {
                    next()
                    if (expectItem) report(token.range, if (items.isEmpty()) "Expected a value before ','." else "Remove the extra ','.")
                    expectItem = true
                    comma = token
                }
                token.kind.isValueStart -> {
                    if (!expectItem) report(token.range, "Expected ',' before this value.")
                    items += value(depth + 1, inner)
                    expectItem = false
                    comma = null
                }
                token.kind == JsonTokenKind.INVALID -> {
                    next()
                    expectItem = false
                    comma = null
                }
                else -> {
                    report(token.range, "Unexpected '${textOf(token)}' in a list.")
                    next()
                }
            }
        }
    }

    /** The string node of the string [token]. */
    private fun stringNode(token: JsonToken): JsonNode.StringNode {
        val content = JsonStrings.contentOf(text, token.range)
        return JsonNode.StringNode(token.range, JsonStrings.decode(text, content), content, JsonStrings.isClosed(text, token.range))
    }

    /** Skips an unquoted key (the current INVALID token) and, after a `:`, its value; the lexer reported the key. */
    private fun skipUnquotedMember(depth: Int, closers: Set<JsonTokenKind>) {
        next()
        if (peek()?.kind != JsonTokenKind.NAME_SEPARATOR) return
        next()
        if (peek()?.kind?.isValueStart == true && !startsMember(index)) value(depth + 1, closers)
    }

    /** Skips the current token; a bracket that opens a container skips the whole container. */
    private fun skip(depth: Int, closers: Set<JsonTokenKind>) {
        val kind = peek()?.kind ?: return
        if (kind == JsonTokenKind.BEGIN_OBJECT || kind == JsonTokenKind.BEGIN_ARRAY) value(depth + 1, closers) else next()
    }

    /** Skips the container opened by [open] (already consumed) and everything nested in it, as one error. */
    private fun tooDeep(open: JsonToken): JsonNode {
        report(open.range, "The value is nested more than $MAX_DEPTH levels deep.")
        var level = 1
        while (level > 0) {
            val token = peek() ?: break
            next()
            when (token.kind) {
                JsonTokenKind.BEGIN_OBJECT, JsonTokenKind.BEGIN_ARRAY -> level++
                JsonTokenKind.END_OBJECT, JsonTokenKind.END_ARRAY -> level--
                else -> Unit
            }
        }
        val range = TextRange(open.range.start, lastEnd)
        return if (open.kind == JsonTokenKind.BEGIN_OBJECT) {
            JsonNode.ObjectNode(range, emptyList(), isClosed = level == 0)
        } else {
            JsonNode.ArrayNode(range, emptyList(), isClosed = level == 0)
        }
    }

    /** True if the token at [at] is a string followed by `:`, i.e. starts the next member. */
    private fun startsMember(at: Int): Boolean =
        tokens.getOrNull(at)?.kind == JsonTokenKind.STRING && tokens.getOrNull(at + 1)?.kind == JsonTokenKind.NAME_SEPARATOR

    private fun peek(): JsonToken? = tokens.getOrNull(index)

    private fun next(): JsonToken = tokens[index++].also { lastEnd = it.range.end }

    private fun textOf(token: JsonToken): String = text.substring(token.range.start, token.range.end)

    /** Reports [message] at the end of the last token, once per text. */
    private fun atEnd(message: String) {
        if (endReported) return
        endReported = true
        report(TextRange.empty(tokens.lastOrNull()?.range?.end ?: 0), message)
    }

    private fun report(range: TextRange, message: String) {
        found += Diagnostic(range, Severity.ERROR, message, DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)
    }

    private companion object {
        /** The deepest nesting read; environment descriptors nest three levels. */
        const val MAX_DEPTH = 64
    }
}
