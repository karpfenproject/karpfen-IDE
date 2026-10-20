package io.karpfen.ide.core.lang.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange

/**
 * A completion template: [label] as shown (e.g. `STATE "…" { }`) and the [template] to insert, in which [CARET] marks
 * where the caret goes after accepting (e.g. `STATE "$0" { }`). Immutable; the per-language keyword tables are lists of
 * snippets.
 */
internal data class Snippet(
    val label: String,
    val template: String,
    val kind: CompletionKind = CompletionKind.SNIPPET,
    val detail: String? = null,
) {
    /** The item replacing [replaceRange]; [rank] orders it before items with a greater rank. */
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

        /** A plain keyword (inserted as written, caret after it). */
        fun keyword(keyword: String, detail: String? = null): Snippet = Snippet(keyword, keyword, CompletionKind.KEYWORD, detail)
    }
}

/**
 * A named proposal (a type, member, state, macro, event, object or path segment): inserted as [name], or quoted when
 * the caret is not inside a string literal yet.
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

/** The sort key of [label] at [rank]: lower ranks first, then the label ignoring case. */
internal fun sortKey(rank: Int, label: String): String = "$rank:${label.lowercase()}"
