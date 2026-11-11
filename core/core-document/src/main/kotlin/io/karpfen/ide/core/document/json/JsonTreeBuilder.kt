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
package io.karpfen.ide.core.document.json

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange

/**
 * Builds the [JsonNode] tree of a token list by recursive descent and recovers from every error. A broken text
 * therefore still yields the tree of everything around the error. Each instance reads one text, once.
 *
 * These rules govern the recovery:
 * - A missing `,` between members or items is reported at the member or item that follows, which is then read.
 * - A key without `:` is reported at the key. If a value follows that does not start the next member, it is read as the
 *   key's value. A string followed by `:` starts the next member.
 * - A missing value is reported at the `:` and leaves [JsonNode.Member.value] null. An INVALID token in the place of a
 *   value or member is consumed silently, because the lexer reported it already. An unquoted key with its value is
 *   skipped the same way.
 * - A closing bracket of an enclosing container ends every container opened since, and each of them is reported once
 *   as not closed. A closing bracket that closes nothing is reported and skipped.
 * - The end of the text ends every open container and missing part, with one error at the end of the last token.
 * - Tokens before the root value are skipped, so one stray keystroke at the top of a file keeps the tree.
 *   [JsonRootStart] documents where the root starts, also after a stray string, number or literal. An INVALID token is
 *   skipped silently. Any other token is reported once as standing before the value. A text without any value is one
 *   error, at its first stray token or, for an empty text, at its end.
 * - Members at the top level whose `{` is missing are read as the root object by [bracelessObject].
 * - Content after the root value is one error.
 * - Containers nested deeper than [MAX_DEPTH] are skipped as one error, so the recursion stays far from the stack
 *   limit.
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
        val start = JsonRootStart.of(tokens)
        val strays = skipUntil(start?.index ?: tokens.size)
        if (start == null) {
            reportMissingRoot(strays.firstOrNull())
            return null
        }
        strays.forEach { report(it.range, "Unexpected '${textOf(it)}' before the JSON value.") }
        val root = if (start.isBraceless) bracelessObject(afterStrays = start.index > 0) else value(depth = 0, closers = emptySet())
        tokens.drop(index).firstOrNull { it.kind != JsonTokenKind.INVALID }?.let { extra ->
            report(extra.range, "Unexpected '${textOf(extra)}' after the end of the JSON value.")
        }
        return root
    }

    /** Consumes the tokens before the one at [end] and returns those the lexer accepted. */
    private fun skipUntil(end: Int): List<JsonToken> = buildList {
        while (index < end) next().takeIf { it.kind != JsonTokenKind.INVALID }?.let { add(it) }
    }

    /**
     * The root object whose members stand at the top level without `{`, as [JsonRootStart.isBraceless] says. It runs
     * from its first member to the end of its last one. It is never [JsonNode.Container.isClosed], because no `{` pairs
     * with the `}` that ends it. That `}` is consumed as the partner of the missing brace. The missing brace is
     * reported at the first member, unless tokens stand before it, as [afterStrays] says. Their error then points at
     * the cause, which is often a quote typed in front of the `{` that turned the brace into a string.
     */
    private fun bracelessObject(afterStrays: Boolean): JsonNode.ObjectNode {
        val firstKey = tokens[index]
        if (!afterStrays) report(firstKey.range, "Expected '{' before this member.")
        val members = members(depth = 0, closers = emptySet()).members
        return JsonNode.ObjectNode(TextRange(firstKey.range.start, members.last().range.end), members, isClosed = false)
    }

    /**
     * Reports that the text holds no value. The error sits at [firstStray], the first token that is no value, or at
     * the end of an empty text. A text of only INVALID tokens gets no error of its own, because the lexer reported each
     * of them.
     */
    private fun reportMissingRoot(firstStray: JsonToken?) {
        when {
            firstStray != null -> report(firstStray.range, "Expected a JSON value.")
            tokens.isEmpty() -> atEnd("Expected a JSON value.")
        }
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

    /** The object opened by [open]. [closers] are the closing brackets of the enclosing containers. */
    private fun objectNode(open: JsonToken, depth: Int, closers: Set<JsonTokenKind>): JsonNode {
        if (depth >= MAX_DEPTH) return tooDeep(open)
        val read = members(depth, closers)
        return JsonNode.ObjectNode(TextRange(open.range.start, lastEnd), read.members, read.isClosed)
    }

    /**
     * The members of an object. They are read up to the `}` that closes the object, which is consumed. Reading also
     * stops at the end of the text or at a bracket of [closers], the closing brackets of the enclosing containers. Such
     * a bracket is reported and left in place.
     */
    private fun members(depth: Int, closers: Set<JsonTokenKind>): MembersRead {
        val members = ArrayList<JsonNode.Member>()
        val inner = closers + JsonTokenKind.END_OBJECT
        var expectMember = true
        var comma: JsonToken? = null
        while (true) {
            val token = peek()
            when {
                token == null -> {
                    atEnd("The object is not closed; add '}'.")
                    return MembersRead(members, isClosed = false)
                }
                token.kind == JsonTokenKind.END_OBJECT -> {
                    next()
                    if (comma != null && members.isNotEmpty()) report(comma.range, "Remove the ',' before '}'; JSON allows no trailing comma.")
                    return MembersRead(members, isClosed = true)
                }
                token.kind in closers -> {
                    report(token.range, "Expected '}' to close the object before '${textOf(token)}'.")
                    return MembersRead(members, isClosed = false)
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
            val value = if (separator.kind.isValueStart && !tokens.startsMemberAt(index)) value(depth + 1, closers) else null
            return JsonNode.Member(key, colon = null, value = value)
        }
        next()
        val colon = separator.range
        val start = peek()
        return when {
            start == null -> JsonNode.Member(key, colon, null).also { atEnd("Expected a value after ':'.") }
            start.kind.isValueStart && !tokens.startsMemberAt(index) -> JsonNode.Member(key, colon, value(depth + 1, closers))
            start.kind == JsonTokenKind.INVALID -> JsonNode.Member(key, colon, null).also { next() }
            else -> JsonNode.Member(key, colon, null).also { report(colon, "Expected a value after ':'.") }
        }
    }

    /** The array opened by [open]. [closers] are the closing brackets of the enclosing containers. */
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

    /**
     * Skips an unquoted key, which is the current INVALID token, and after a `:` also its value. The lexer reported the
     * key already.
     */
    private fun skipUnquotedMember(depth: Int, closers: Set<JsonTokenKind>) {
        next()
        if (peek()?.kind != JsonTokenKind.NAME_SEPARATOR) return
        next()
        if (peek()?.kind?.isValueStart == true && !tokens.startsMemberAt(index)) value(depth + 1, closers)
    }

    /** Skips the current token. A bracket that opens a container skips the whole container. */
    private fun skip(depth: Int, closers: Set<JsonTokenKind>) {
        val kind = peek()?.kind ?: return
        if (kind.opensContainer) value(depth + 1, closers) else next()
    }

    /** Skips the container opened by [open], which is already consumed, and everything nested in it, as one error. */
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

    /**
     * The members of an object as [members] read them.
     *
     * @property isClosed true if the `}` that closes the object was read.
     */
    private class MembersRead(val members: List<JsonNode.Member>, val isClosed: Boolean)

    private companion object {
        /** The deepest nesting that is read. Environment descriptors nest three levels. */
        const val MAX_DEPTH = 64
    }
}
