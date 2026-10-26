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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.action.StringLiterals
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries

/**
 * The auto-triggers of completion, decided on the text right after typing, pure and stateless:
 *
 * - [CompletionTrigger.IDENTIFIER]: one identifier character typed directly after another one (the word rule of the
 *   caret model, `[A-Za-z0-9_]`);
 * - [CompletionTrigger.QUOTE]: a `"` that opened a string (the caret is inside a string of its line afterwards, also
 *   when the quote was auto-closed); a `"` that closed a string does not trigger. Whether the string position is
 *   completable is the source's answer: no items keep completion closed;
 * - [CompletionTrigger.PATH_START]: `$(` before the caret after typing `(`;
 * - [CompletionTrigger.ARROW]: `->` before the caret after typing `>`.
 *
 * A request whose text changed before its items arrived completes the current text only while [stillHolds], and an open
 * session closes once it no longer does.
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

    /**
     * True if a request made at [offset] of text [version] still completes at [caret] in the current [text]: nothing is
     * selected and only identifier characters lie between the request position (mapped into the current text, text
     * inserted at it counting as typed after it) and the caret — the user kept typing or deleting in the word being
     * completed, or the text changed elsewhere. A line break, a space or other punctuation typed since, or a caret moved
     * out of the word, ends the request and the session it opened: no trigger fired at the caret. False if the history
     * no longer reaches [version].
     */
    fun stillHolds(text: EditableText, version: Long, offset: Int, caret: Caret): Boolean {
        if (caret.hasSelection) return false
        val current = text.snapshot.value
        if (version > current.version) return false
        val mapped = text.mapOffset(offset, version, current.version, Bias.LEFT) ?: return false
        return (minOf(mapped, caret.position) until maxOf(mapped, caret.position)).all { WordBoundaries.isIdentifierChar(current[it]) }
    }
}
