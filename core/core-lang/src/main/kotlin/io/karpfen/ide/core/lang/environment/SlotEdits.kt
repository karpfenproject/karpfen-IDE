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
package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.json.JsonDocument
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonTokenKind
import io.karpfen.ide.core.text.TextRange

/**
 * Works out the [SlotEdit] of a place that [DescriptorCaret] found in the [document] of [text], for the caret at
 * [offset], so that accepting a proposal leaves the descriptor valid JSON around it:
 * - in blank space, a bare word, a number or a literal, the proposal replaces that and brings its own quotes;
 * - in a closed string it replaces the content and the quotes stay, unless more has to follow the string (the `:` of
 *   a key that becomes a member, the `,` in front of the next item): then it also takes in the closing quote and
 *   brings it;
 * - in a string without closing quote, which the lexer ends at the line break, it replaces the content only up to the
 *   end of the word at the caret and brings the closing quote, so the rest of the line (a `,`, a `}`) stays where it
 *   is. The modeling languages follow the same rule (`CaretContext.replaceRange`);
 * - where the member or item the proposal writes stands in front of another one with no comma in between, the
 *   proposal brings the `,`. Blank lines between members are where keys are usually added, so this is the common case.
 *
 * Pure; one per search of [DescriptorCaret].
 */
internal class SlotEdits(private val document: JsonDocument, private val text: String, private val offset: Int) {

    /**
     * The edit for a new member or item at [range], blank space or a bare word, of a [container] (null for the root);
     * [leading] goes in front of it.
     */
    fun outside(range: TextRange, container: Container?, leading: String = ""): SlotEdit =
        SlotEdit(range, Quotes.OWN, leading, trailingAfter(range.end, container))

    /** The edit on the number or literal [node], a member value or item of a [container] (null for the root). */
    fun on(node: JsonNode, container: Container?): SlotEdit =
        SlotEdit(node.range, Quotes.OWN, trailing = trailingAfter(node.range.end, container))

    /**
     * The edit in the [string] at the caret. [container] holds the member or item the proposal ends, which may need a
     * `,` behind it; null where the proposal ends nothing (a key that keeps its value). [continues] is true if the
     * proposal writes past the string's closing quote, as a key that gets its `:` does.
     */
    fun inString(string: JsonNode.StringNode, container: Container?, continues: Boolean): SlotEdit {
        if (!string.isClosed) {
            val range = TextRange(string.content.start, wordEnd(string.content))
            return SlotEdit(range, Quotes.OPENED, trailing = trailingAfter(range.end, container))
        }
        val trailing = trailingAfter(string.range.end, container)
        return if (continues || trailing.isNotEmpty()) {
            SlotEdit(TextRange(string.content.start, string.range.end), Quotes.OPENED, trailing = trailing)
        } else {
            SlotEdit(string.content, Quotes.KEPT)
        }
    }

    /**
     * Where the word at the caret ends in [content], the content of a string without closing quote: at the first blank
     * or JSON punctuation from the caret on, else at the end of the content.
     */
    private fun wordEnd(content: TextRange): Int {
        var end = offset.coerceIn(content.start, content.end)
        while (end < content.end && !text[end].isWhitespace() && text[end] !in WORD_STOPS) end++
        return end
    }

    /**
     * The separator a member or item of [container] that ends at [end] needs behind it: `,` when the next token
     * starts another member or item of the container, `, ` when that one starts right at [end]; nothing when a `,`, a
     * closing bracket, other text or nothing follows, and nothing without a container. When [end] lies inside a token,
     * in a string without closing quote, the rest of that string comes first: only a blank rest lets the next line
     * decide.
     */
    private fun trailingAfter(end: Int, container: Container?): String {
        if (container == null) return ""
        val cut = document.tokenAt(end)?.takeIf { it.range.start < end }
        if (cut != null && text.substring(end, cut.range.end).isNotBlank()) return ""
        val from = cut?.range?.end ?: end
        val next = document.tokens.firstOrNull { it.range.start >= from } ?: return ""
        return when {
            !container.startsNext(next.kind) -> ""
            next.range.start == end -> ", "
            else -> ","
        }
    }

    private companion object {
        /** The characters that end a word in a string without closing quote: JSON punctuation, quote and backslash. */
        const val WORD_STOPS = ",:{}[]\"\\"
    }
}

/** What holds a member or item, by what starts the next one in it. */
internal enum class Container {
    /** An object: its next member starts with its key string. */
    OBJECT,

    /** A list: its next item starts with any value. */
    LIST,
    ;

    /** True if a token of [kind] starts the next member or item. */
    fun startsNext(kind: JsonTokenKind): Boolean = when (this) {
        OBJECT -> kind == JsonTokenKind.STRING
        LIST -> kind.isValueStart
    }
}
