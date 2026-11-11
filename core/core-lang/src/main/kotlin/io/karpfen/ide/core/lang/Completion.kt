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
package io.karpfen.ide.core.lang

import io.karpfen.ide.core.text.TextRange

/**
 * One completion proposal. Providers do not filter by prefix. The editor filters by the text between
 * `replaceRange.start` and the caret, so [replaceRange] covers the whole identifier or string content under the caret.
 *
 * @property label the text shown in the list and used for filtering.
 * @property insertText the text that replaces [replaceRange] when the proposal is accepted.
 * @property replaceRange the identifier or string content under the caret, in the coordinates of the request's text.
 * @property caretOffsetInInsert the caret position after accepting, relative to the start of [insertText]. A snippet
 *   uses it to put the caret inside its first quotes, for example. Null puts the caret at the end of the insertion.
 * @property detail a short secondary text shown next to the label, such as a type.
 * @property documentation a longer text for a details pane or tooltip.
 * @property sortKey the tie-breaker for equally good matches. The editor sorts by match quality, then by sortKey, then
 *   by label.
 */
public data class CompletionItem(
    public val label: String,
    public val kind: CompletionKind,
    public val insertText: String,
    public val replaceRange: TextRange,
    public val caretOffsetInInsert: Int?,
    public val detail: String?,
    public val documentation: String?,
    public val sortKey: String,
) {
    init {
        require(caretOffsetInInsert == null || caretOffsetInInsert in 0..insertText.length) {
            "caretOffsetInInsert $caretOffsetInInsert must lie within the insert text (length ${insertText.length})"
        }
    }
}

/** The category of a [CompletionItem]. The completion popup shows it as a badge. */
public enum class CompletionKind {
    /** A keyword. */
    KEYWORD,

    /** A template of several tokens, such as `type "Name" "Description" { }`. */
    SNIPPET,

    /** A type name. */
    TYPE,

    /** `number`, `string`, `boolean`. */
    PRIMITIVE_TYPE,

    /** A simple property key, or a key of an environment descriptor. */
    PROPERTY,

    /** A `has`/`knows` relation key. */
    RELATION,

    /** An object key. */
    OBJECT,

    /** A state name. */
    STATE,

    /** A macro name. */
    MACRO,

    /** An event name. */
    EVENT,

    /** A segment of a model path. */
    PATH_SEGMENT,

    /** A `WITH ... AS` binding or a macro parameter. */
    BINDING,

    /** A file path, such as a file named in an environment descriptor. */
    FILE,

    /** A typical value of a key, such as a number or `true` in an environment descriptor. */
    VALUE,
}
