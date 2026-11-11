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
 * The auto-triggers of completion. They are decided on the text right after typing. The object holds pure functions and
 * no state.
 *
 * [CompletionTrigger.IDENTIFIER] fires when one identifier character is typed directly after another one. Identifier
 * characters are `[A-Za-z0-9_]`, the same word rule the caret model uses.
 *
 * [CompletionTrigger.QUOTE] fires on a `"` that opened a string. Afterwards the caret is inside a string of its line,
 * also when the quote was auto-closed. A `"` that closed a string does not trigger. Whether the string position can be
 * completed is up to the source. If it returns no items, completion stays closed.
 *
 * [CompletionTrigger.PATH_START] fires when `$(` is before the caret after typing `(`. [CompletionTrigger.ARROW] fires
 * when `->` is before the caret after typing `>`.
 *
 * If the text changed before the items of a request arrived, the request completes the current text only while
 * [stillHolds] is true. An open session closes as soon as [stillHolds] is false.
 */
internal object CompletionTriggers {

    /** Returns the trigger fired by [typed], which was just inserted before [caret] in [snapshot], or null. */
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
     * True if a request made at [offset] of text [version] still completes at [caret] in the current [text]. That is
     * the case when nothing is selected and only identifier characters lie between the request position and the caret.
     * The request position is mapped into the current text, and text inserted at it counts as typed after it. So the
     * request holds while the user keeps typing or deleting in the word being completed, or when the text changed
     * elsewhere.
     *
     * A line break, a space or other punctuation typed since, or a caret moved out of the word, ends the request and the
     * session it opened, because no trigger fired at the caret. The function also returns false if the history no
     * longer reaches [version].
     */
    fun stillHolds(text: EditableText, version: Long, offset: Int, caret: Caret): Boolean {
        if (caret.hasSelection) return false
        val current = text.snapshot.value
        if (version > current.version) return false
        val mapped = text.mapOffset(offset, version, current.version, Bias.LEFT) ?: return false
        return (minOf(mapped, caret.position) until maxOf(mapped, caret.position)).all { WordBoundaries.isIdentifierChar(current[it]) }
    }
}
