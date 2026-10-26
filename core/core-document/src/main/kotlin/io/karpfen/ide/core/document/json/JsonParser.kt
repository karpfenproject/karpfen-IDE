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

/**
 * A JSON text as [JsonParser] reads it.
 *
 * @property root the value the text holds, as far as it is there, or null if the text holds none.
 * @property tokens every token of the text ([JsonLexer]), in text order.
 * @property diagnostics the LEXER errors of the tokens and the PARSER errors (`syntax-error`) of the structure, sorted
 *   with [Diagnostic.ORDER]; empty exactly for a text that is one valid JSON value.
 */
public data class JsonDocument(
    public val root: JsonNode?,
    public val tokens: List<JsonToken>,
    public val diagnostics: List<Diagnostic>,
) {
    /** True if the text is one valid JSON value. */
    public val isSyntaxClean: Boolean get() = diagnostics.isEmpty()

    /** The token containing [offset] (`start <= offset < end`), or null. */
    public fun tokenAt(offset: Int): JsonToken? = tokens.getOrNull(firstEndingAfter(offset))?.takeIf { offset in it.range }

    /** The last token that ends at or before [offset], or null if none does. */
    public fun tokenBefore(offset: Int): JsonToken? {
        val index = firstEndingAfter(offset)
        return tokens.getOrNull(index - 1)
    }

    /** The index of the first token whose end is greater than [offset] (binary search); `tokens.size` if none. */
    private fun firstEndingAfter(offset: Int): Int {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokens[mid].range.end <= offset) low = mid + 1 else high = mid
        }
        return low
    }
}

/**
 * Reads JSON text into a [JsonDocument], written for the IDE instead of taken from a JSON library: the editor needs the
 * position of every value and a tree of every text, also of a broken one while the user types. Never throws, stateless
 * and thread-safe; the tree is built by [JsonTreeBuilder], which documents how it recovers from errors.
 */
public object JsonParser {

    /** The document of [text]. */
    public fun parse(text: String): JsonDocument {
        val lexed = JsonLexer.lex(text)
        val builder = JsonTreeBuilder(text, lexed.tokens)
        val root = builder.document()
        return JsonDocument(root, lexed.tokens, (lexed.diagnostics + builder.diagnostics).sortedWith(Diagnostic.ORDER))
    }
}
