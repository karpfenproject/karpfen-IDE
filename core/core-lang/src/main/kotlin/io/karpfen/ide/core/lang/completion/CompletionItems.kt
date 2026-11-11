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
 * A completion template. [label] is what the list shows, such as `STATE "…" { }`. [template] is what gets inserted. In
 * it, [CARET] marks where the caret goes after accepting, as in `STATE "$0" { }`. Snippets are immutable, and the
 * keyword tables of each language are lists of snippets.
 */
internal data class Snippet(
    val label: String,
    val template: String,
    val kind: CompletionKind = CompletionKind.SNIPPET,
    val detail: String? = null,
) {
    /** The item that replaces [replaceRange]. [rank] orders it before items with a greater rank. */
    fun item(replaceRange: TextRange, rank: Int = 1): CompletionItem {
        val caret = template.indexOf(CARET)
        return CompletionItem(
            label = label,
            kind = kind,
            insertText = template.replace(CARET, ""),
            replaceRange = replaceRange,
            caretOffsetInInsert = caret.takeIf { it >= 0 },
            detail = detail,
            documentation = null,
            sortKey = sortKey(rank, label),
        )
    }

    companion object {
        /** The caret marker of [template]. */
        const val CARET: String = "\$0"

        /** A plain keyword. It is inserted as written, with the caret after it. */
        fun keyword(keyword: String, detail: String? = null): Snippet = Snippet(keyword, keyword, CompletionKind.KEYWORD, detail)
    }
}

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
