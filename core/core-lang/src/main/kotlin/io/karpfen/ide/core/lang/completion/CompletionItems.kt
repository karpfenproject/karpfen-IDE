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
package io.karpfen.ide.core.lang.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange

/**
 * A completion snippet. [label] is what the list shows, such as `STATE "…" { }`. [template] is what gets inserted. In
 * it, [CARET] marks where the caret goes after accepting, as in `STATE "$0" { }`, and `${n:word}` marks a placeholder
 * as [TemplateText.parse] reads it. Snippets are immutable, and the keyword tables of each language are lists of
 * snippets.
 *
 * @property rank the rank of [item] unless the caller gives another. Lower ranks come first among equal matches.
 */
internal data class Snippet(
    val label: String,
    val template: String,
    val kind: CompletionKind = CompletionKind.SNIPPET,
    val detail: String? = null,
    val rank: Int = 1,
) {
    /** The item that replaces [replaceRange]. [rank] orders it before items with a greater rank. */
    fun item(replaceRange: TextRange, rank: Int = this.rank): CompletionItem =
        templateItem(label, kind, TemplateText.parse(template), replaceRange, detail, documentation = null, rank = rank)

    companion object {
        /** The caret marker of [template]. */
        const val CARET: String = TemplateText.CARET

        /** The detail of every template, which tells it apart from the one-line snippet of the same keyword. */
        const val TEMPLATE_DETAIL: String = "template"

        /**
         * The rank of a template. It is greater than the ranks of the one-line snippets and names, so among equally good
         * matches the one-line snippet of a keyword stays before its template, and Enter on the first entry of the list
         * inserts the same as without templates.
         */
        const val TEMPLATE_RANK: Int = 5

        /** A plain keyword. It is inserted as written, with the caret after it. */
        fun keyword(keyword: String, detail: String? = null): Snippet = Snippet(keyword, keyword, CompletionKind.KEYWORD, detail)

        /**
         * A template of several lines. [label] is one line that tells what it inserts, and it starts with the keyword
         * that the user types. [lines] are joined with line breaks. Each line after the first is indented by four
         * spaces per level relative to the first one, and the editor adds the indentation of the line it is inserted
         * on.
         */
        fun template(label: String, vararg lines: String): Snippet =
            Snippet(label, lines.joinToString("\n"), CompletionKind.SNIPPET, TEMPLATE_DETAIL, TEMPLATE_RANK)
    }
}

/**
 * The item of [text] that replaces [replaceRange]. The caret goes to `text.caret`, and the placeholders of [text] become
 * the placeholders of the item.
 */
internal fun templateItem(
    label: String,
    kind: CompletionKind,
    text: TemplateText,
    replaceRange: TextRange,
    detail: String?,
    documentation: String?,
    rank: Int,
): CompletionItem = CompletionItem(
    label = label,
    kind = kind,
    insertText = text.text,
    replaceRange = replaceRange,
    caretOffsetInInsert = text.caret,
    detail = detail,
    documentation = documentation,
    sortKey = sortKey(rank, label),
    placeholders = text.placeholders,
)

/**
 * A named proposal, such as a type, member, state, macro, event, object or path segment. It is inserted as [name], or
 * in quotes when [quoted] is true because the caret is not inside a string literal yet.
 */
internal fun nameItem(
    name: String,
    kind: CompletionKind,
    replaceRange: TextRange,
    detail: String? = null,
    documentation: String? = null,
    rank: Int = 1,
    quoted: Boolean = false,
): CompletionItem = CompletionItem(
    label = name,
    kind = kind,
    insertText = if (quoted) "\"$name\"" else name,
    replaceRange = replaceRange,
    caretOffsetInInsert = null,
    detail = detail,
    documentation = documentation,
    sortKey = sortKey(rank, name),
)

/** The sort key of [label] at [rank]. Lower ranks come first, then the label ignoring case. */
internal fun sortKey(rank: Int, label: String): String = "$rank:${label.lowercase()}"
