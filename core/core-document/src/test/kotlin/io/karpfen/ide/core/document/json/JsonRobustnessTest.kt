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
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the robustness of the JSON lexer, tokenizer and parser: they never throw on deterministic mutations of every
 * descriptor fixture and on every prefix of the valid one, and every result keeps the documented invariants (see
 * [assertJsonInvariants]).
 */
class JsonRobustnessTest {

    @Test
    fun `lexer, tokenizer and parser never throw on mutations of the descriptor fixtures`(reporter: TestReporter) {
        var checked = 0
        var broken = 0
        for (name in DescriptorExamples.names()) {
            val example = DescriptorExamples.text(name)
            Mutations.random(example, seed = SEED + name.length, count = MUTATIONS).forEach { mutation ->
                val document = parseChecked(mutation.text, "$name, $mutation")
                checked++
                if (!document.isSyntaxClean) broken++
            }
        }
        reporter.publishEntry("JSON mutations", "$checked mutated descriptors parsed, $broken with syntax errors")
    }

    @Test
    fun `lexer, tokenizer and parser never throw on any prefix of a descriptor`(reporter: TestReporter) {
        val text = DescriptorExamples.text(DescriptorExamples.VALID)
        var count = 0
        Mutations.prefixes(text).forEach { prefix ->
            val document = parseChecked(prefix, "prefix of length ${prefix.length}")
            if (prefix.length in 1 until text.trimEnd().length) assertTrue(!document.isSyntaxClean, "the prefix of length ${prefix.length} is incomplete")
            count++
        }
        assertEquals(text.length + 1, count)
        assertTrue(parseChecked(text, "the whole descriptor").isSyntaxClean)
        reporter.publishEntry("JSON prefixes", "$count prefixes parsed")
    }

    /** Parses [text], failing on an exception, checks the invariants and returns the document. */
    private fun parseChecked(text: String, label: String): JsonDocument {
        val document = try {
            JsonParser.parse(text)
        } catch (failure: Exception) {
            fail("parsing threw for $label: $failure", failure)
        }
        assertJsonInvariants(text, document, label)
        return document
    }

    private companion object {
        const val MUTATIONS = 3_000
        const val SEED = 20_261_022L
    }
}

/**
 * The invariants of a [JsonDocument] of [text]:
 * - tokens are non-empty, inside the text, sorted, disjoint and cover every character that is not JSON whitespace;
 *   every INVALID token has a LEXER error over exactly its range, and the [JsonTokenizer] yields the same ranges;
 * - diagnostics are sorted, inside the text and come from the lexer or the parser; no diagnostics exactly when the
 *   document is syntax-clean;
 * - every node lies inside the text and inside its parent, members and items are in document order and disjoint, a
 *   member's key comes before its colon and its value, and a string's content lies inside the string.
 */
internal fun assertJsonInvariants(text: String, document: JsonDocument, label: String) {
    val covered = IntArray(text.length)
    var previousEnd = 0
    for (token in document.tokens) {
        assertTrue(token.range.start >= previousEnd && token.range.end <= text.length, "token $token out of order in $label")
        previousEnd = token.range.end
        for (k in token.range.start until token.range.end) covered[k]++
    }
    for (k in text.indices) {
        if (!JsonLexer.isWhitespace(text[k])) assertEquals(1, covered[k], "character ${text[k].code} at $k in $label")
    }
    val lexerErrors = document.diagnostics.filter { it.source == DiagnosticSource.LEXER }.map { it.range }.toSet()
    document.tokens.filter { it.kind == JsonTokenKind.INVALID }.forEach { token ->
        assertTrue(token.range in lexerErrors, "invalid token $token without its error in $label")
    }
    val tokenized = JsonTokenizer().tokenize(TextSnapshot(text, 1))
    assertEquals(document.tokens.map { it.range }, tokenized.tokens.map { it.range }, label)
    assertEquals(document.tokens.count { it.kind == JsonTokenKind.INVALID }, tokenized.tokens.count { it.kind == TokenKind.ERROR }, label)

    assertEquals(document.diagnostics.sortedWith(Diagnostic.ORDER), document.diagnostics, "diagnostic order in $label")
    document.diagnostics.forEach { diagnostic ->
        assertTrue(diagnostic.range.end <= text.length, "$diagnostic outside the text in $label")
        assertTrue(diagnostic.source == DiagnosticSource.LEXER || diagnostic.source == DiagnosticSource.PARSER, "$diagnostic in $label")
    }
    assertEquals(document.diagnostics.isEmpty(), document.isSyntaxClean)
    document.root?.let { assertNode(it, TextRange(0, text.length), label) }
}

/** The tree invariants of [node] inside [parent] (see [assertJsonInvariants]). */
private fun assertNode(node: JsonNode, parent: TextRange, label: String) {
    assertTrue(node.range in parent, "${node.range} outside its parent $parent in $label")
    when (node) {
        is JsonNode.ObjectNode -> {
            assertOrdered(node.members.map { it.range }, label)
            node.members.forEach { member ->
                assertTrue(member.range in node.range, "member ${member.range} outside ${node.range} in $label")
                assertNode(member.key, member.range, label)
                member.colon?.let { assertTrue(it.start >= member.key.range.end && it in member.range, "colon $it in $label") }
                member.value?.let { value ->
                    assertTrue(value.range.start >= (member.colon ?: member.key.range).end, "value before key in $label")
                    assertNode(value, member.range, label)
                }
            }
        }
        is JsonNode.ArrayNode -> {
            assertOrdered(node.items.map { it.range }, label)
            node.items.forEach { assertNode(it, node.range, label) }
        }
        is JsonNode.StringNode -> assertTrue(node.content in node.range, "string content outside the string in $label")
        is JsonNode.NumberNode, is JsonNode.LiteralNode -> Unit
    }
}

/** Asserts that [ranges] are in document order and disjoint. */
private fun assertOrdered(ranges: List<TextRange>, label: String) {
    ranges.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "$a and $b overlap or are out of order in $label") }
}
