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
 * [offset]. The edit makes sure that accepting a proposal leaves the descriptor valid JSON around it.
 * - In blank space, a bare word, a number or a literal, the proposal replaces that and brings its own quotes.
 * - In a closed string the proposal replaces the content and the quotes stay. When more has to follow the string, it
 *   also takes in the closing quote and brings it. Examples are the `:` of a key that becomes a member and the `,` in
 *   front of the next item.
 * - The lexer ends a string without a closing quote at the line break. In such a string the proposal replaces the
 *   content only up to the end of the word at the caret and brings the closing quote. So the rest of the line, such
 *   as a `,` or a `}`, stays where it is. The modeling languages follow the same rule in `CaretContext.replaceRange`.
 * - When the member or item the proposal writes stands in front of another one without a comma in between, the
 *   proposal brings the `,`. Keys are usually added in blank lines between members, so this is the common case.
 *
 * It has no side effects. Each search of [DescriptorCaret] makes its own.
 */
internal class SlotEdits(private val document: JsonDocument, private val text: String, private val offset: Int) {

    /**
     * The edit for a new member or item of a [container] at [range], which is blank space or a bare word. The
     * [container] is null for the root. [leading] goes in front of the proposal.
     */
    fun outside(range: TextRange, container: Container?, leading: String = ""): SlotEdit =
        SlotEdit(range, Quotes.OWN, leading, trailingAfter(range.end, container))

    /** The edit on the number or literal [node], a member value or item of a [container] that is null for the root. */
    fun on(node: JsonNode, container: Container?): SlotEdit =
        SlotEdit(node.range, Quotes.OWN, trailing = trailingAfter(node.range.end, container))

    /**
     * The edit in the [string] at the caret. [container] holds the member or item the proposal ends, which may need a
     * `,` behind it. It is null where the proposal ends nothing, as for a key that keeps its value. [continues] is true
     * if the proposal writes past the closing quote of the string, as a key that gets its `:` does.
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
     * Where the word at the caret ends in [content], the content of a string without a closing quote. It ends at the
     * first blank or JSON punctuation from the caret on, or else at the end of the content.
     */
    private fun wordEnd(content: TextRange): Int {
        var end = offset.coerceIn(content.start, content.end)
        while (end < content.end && !text[end].isWhitespace() && text[end] !in WORD_STOPS) end++
        return end
    }

    /**
     * The separator that a member or item of [container] ending at [end] needs behind it. It is `,` when the next
     * token starts another member or item of the container, and `, ` when that one starts right at [end]. It is empty
     * when a `,`, a closing bracket, other text or nothing follows, and when there is no container. [end] can lie
     * inside a token, in a string without a closing quote. Then the rest of that string comes first, and only a blank
     * rest lets the next line decide.
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
        /**
         * The characters that end a word in a string without a closing quote. They are JSON punctuation, quote and
         * backslash.
         */
        const val WORD_STOPS = ",:{}[]\"\\"
    }
}

/** What holds a member or item, by what starts the next one in it. */
internal enum class Container {
    /** An object. Its next member starts with its key string. */
    OBJECT,

    /** A list. Its next item starts with any value. */
    LIST,
    ;

    /** True if a token of [kind] starts the next member or item. */
    fun startsNext(kind: JsonTokenKind): Boolean = when (this) {
        OBJECT -> kind == JsonTokenKind.STRING
        LIST -> kind.isValueStart
    }
}
