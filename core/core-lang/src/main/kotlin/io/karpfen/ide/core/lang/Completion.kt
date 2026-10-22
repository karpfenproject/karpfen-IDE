package io.karpfen.ide.core.lang

import io.karpfen.ide.core.text.TextRange

/**
 * One completion proposal. Providers do not filter by prefix: the editor filters by the text between
 * `replaceRange.start` and the caret, so [replaceRange] covers the whole identifier or string content under the caret.
 *
 * @property label text shown in the list and used for filtering.
 * @property insertText text that replaces [replaceRange] on accept.
 * @property replaceRange the identifier or string content under the caret (in the request's text coordinates).
 * @property caretOffsetInInsert caret position after accepting, relative to the start of [insertText] (e.g. inside
 *   the first quotes of a snippet); null puts the caret at the end of the insertion.
 * @property detail short secondary text shown next to the label (e.g. a type).
 * @property documentation longer text for a details pane or tooltip.
 * @property sortKey tie-breaker for equally good matches (the editor sorts by match quality, then sortKey, then label).
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

/** Category of a [CompletionItem]; shown as a badge in the completion popup. */
public enum class CompletionKind {
    /** A keyword. */
    KEYWORD,

    /** A multi-token template, e.g. `type "Name" "Description" { }`. */
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

    /** A `WITH … AS` binding or macro parameter. */
    BINDING,

    /** A file path, such as a file named in an environment descriptor. */
    FILE,

    /** A typical value of a key, such as a number or `true` in an environment descriptor. */
    VALUE,
}
