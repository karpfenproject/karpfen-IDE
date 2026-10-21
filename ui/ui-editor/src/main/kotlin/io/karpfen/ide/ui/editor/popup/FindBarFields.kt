package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.karpfen.ide.ui.editor.model.find.FindQuery

/** The toggles of the find bar (spec §7.2: Aa / W / .*), each one flag of [FindQuery]. */
internal enum class FindOption(
    /** The toggle's label. */
    val label: String,
    /** Its accessible description (also the tooltip-like name tests find it by). */
    val description: String,
) {
    /** [FindQuery.caseSensitive]. */
    MATCH_CASE("Aa", "Match Case"),

    /** [FindQuery.wholeWord]. */
    WHOLE_WORD("W", "Whole Words"),

    /** [FindQuery.regex]. */
    REGEX(".*", "Regular Expression"),
    ;

    /** Whether this option is on in [query]. */
    fun isOn(query: FindQuery): Boolean = when (this) {
        MATCH_CASE -> query.caseSensitive
        WHOLE_WORD -> query.wholeWord
        REGEX -> query.regex
    }

    /** [query] with this option switched. */
    fun toggle(query: FindQuery): FindQuery = when (this) {
        MATCH_CASE -> query.copy(caseSensitive = !query.caseSensitive)
        WHOLE_WORD -> query.copy(wholeWord = !query.wholeWord)
        REGEX -> query.copy(regex = !query.regex)
    }
}

/**
 * Hoisted state of the find/replace bar's text fields (Compose-side helper state of the popup; the query itself is the
 * `FindModel`'s [query][io.karpfen.ide.ui.editor.model.find.FindModel.query], which the bar renders): the selection
 * and input-method composition of the query field and the replacement field. One instance per editor composition, so
 * the replacement survives closing and reopening the bar.
 *
 * The query field always shows the model's query text: [queryField] keeps the field as the user edited it while it
 * shows that text, and shows a text the editor set (e.g. seeded from the selection) selected as a whole.
 *
 * @param focusRequest the value of `EditorOverlayState.findFocusRequests` at creation: requests up to it are handled
 *   (a bar composed again, e.g. after switching tabs, does not take focus from the text area).
 */
@Stable
internal class FindBarFields(focusRequest: Long) {

    /** The query field as last edited or selected (text, selection, IME composition). */
    private var editedQuery: TextFieldValue by mutableStateOf(TextFieldValue())

    /** The replacement field (replace mode). */
    var replacementField: TextFieldValue by mutableStateOf(TextFieldValue())

    private var handledFocusRequest = focusRequest

    /** The query field showing the model's query [text]: the edited field while it shows [text], else [text] selected. */
    fun queryField(text: String): TextFieldValue =
        if (editedQuery.text == text) editedQuery else TextFieldValue(text, TextRange(0, text.length))

    /**
     * The user edited the query field (showing [shownText]) to [value]; returns the new query text to send to the model,
     * or null if only the selection or composition changed.
     */
    fun editQuery(value: TextFieldValue, shownText: String): String? {
        editedQuery = value
        return value.text.takeIf { it != shownText }
    }

    /**
     * Handles the focus request number [request] (the current `findFocusRequests`) for the model's query [text]: returns
     * false if that request was handled already; otherwise selects the whole query and returns true (the caller then
     * focuses the query field).
     */
    fun takeFocusRequest(request: Long, text: String): Boolean {
        if (request == handledFocusRequest) return false
        handledFocusRequest = request
        editedQuery = TextFieldValue(text, TextRange(0, text.length))
        return true
    }
}
