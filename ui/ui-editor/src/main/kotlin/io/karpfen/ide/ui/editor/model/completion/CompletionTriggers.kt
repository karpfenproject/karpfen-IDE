package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.StringLiterals
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries

/**
 * The auto-triggers of completion (spec §7.1), decided on the text right after typing, pure and stateless:
 *
 * - [CompletionTrigger.IDENTIFIER]: one identifier character typed directly after another one (the word rule of the
 *   caret model, `[A-Za-z0-9_]`);
 * - [CompletionTrigger.QUOTE]: a `"` that opened a string (the caret is inside a string of its line afterwards, also
 *   when the quote was auto-closed); a `"` that closed a string does not trigger. Whether the string position is
 *   completable is the source's answer: no items keep completion closed;
 * - [CompletionTrigger.PATH_START]: `$(` before the caret after typing `(`;
 * - [CompletionTrigger.ARROW]: `->` before the caret after typing `>`.
 */
internal object CompletionTriggers {

    /** The trigger fired by [typed], just inserted before [caret] in [snapshot], or null. */
    fun afterTyping(snapshot: TextSnapshot, caret: Int, typed: String): CompletionTrigger? {
        if (typed.isEmpty() || caret < 1 || caret > snapshot.length || snapshot[caret - 1] != typed.last()) return null
        val text = snapshot.text
        val last = typed.last()
        return when {
            typed.length == 1 && WordBoundaries.isIdentifierChar(last) && caret >= 2 &&
                WordBoundaries.isIdentifierChar(text[caret - 2]) -> CompletionTrigger.IDENTIFIER
            last == '"' && StringLiterals.isInsideString(snapshot, caret) -> CompletionTrigger.QUOTE
            last == '(' && text.startsWith("$(", caret - 2) -> CompletionTrigger.PATH_START
            last == '>' && text.startsWith("->", caret - 2) -> CompletionTrigger.ARROW
            else -> null
        }
    }
}
