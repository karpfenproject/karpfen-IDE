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
 * Hoisted state of the find/replace bar's text fields (Compose-side helper state of the popup; the texts themselves
 * are the `FindModel`'s [query][io.karpfen.ide.ui.editor.model.find.FindModel.query] and
 * [replacement][io.karpfen.ide.ui.editor.model.find.FindModel.replacement], which the bar renders): the selection and
 * input-method composition of the query field and the replacement field, which are view state. One instance per
 * editor composition; a new composition (e.g. after switching tabs) starts with fresh selections over the model's
 * texts.
 *
 * Both fields always show the model's text: a field keeps its value as the user edited it while it shows that text, and
 * shows a text the editor set (e.g. a query seeded from the selection) selected as a whole.
 *
 * @param focusRequest the value of `EditorOverlayState.findFocusRequests` at creation: requests up to it are handled
 *   (a bar composed again, e.g. after switching tabs, does not take focus from the text area).
 */
@Stable
internal class FindBarFields(focusRequest: Long) {

    private val query = ModelTextField()
    private val replacement = ModelTextField()
    private var handledFocusRequest = focusRequest

    /** The query field showing the model's query [text]. */
    fun queryField(text: String): TextFieldValue = query.value(text)

    /**
     * The user edited the query field (showing [shownText]) to [value]; returns the new query text to send to the model,
     * or null if only the selection or composition changed.
     */
    fun editQuery(value: TextFieldValue, shownText: String): String? = query.edit(value, shownText)

    /** The replacement field showing the model's replacement [text]. */
    fun replacementField(text: String): TextFieldValue = replacement.value(text)

    /** Like [editQuery] for the replacement field: returns the new replacement to send to the model, or null. */
    fun editReplacement(value: TextFieldValue, shownText: String): String? = replacement.edit(value, shownText)

    /**
     * Handles the focus request number [request] (the current `findFocusRequests`) for the model's query [text]: returns
     * false if that request was handled already; otherwise selects the whole query and returns true (the caller then
     * focuses the query field).
     */
    fun takeFocusRequest(request: Long, text: String): Boolean {
        if (request == handledFocusRequest) return false
        handledFocusRequest = request
        query.selectAll(text)
        return true
    }
}

/**
 * One text field over a text the model owns: the field as last edited or selected (text, selection, IME composition),
 * shown while it holds the model's text; any other text of the model is shown selected as a whole.
 */
@Stable
private class ModelTextField {
    private var edited: TextFieldValue by mutableStateOf(TextFieldValue())

    /** The field showing the model's [text]. */
    fun value(text: String): TextFieldValue = if (edited.text == text) edited else whole(text)

    /** Records the user's [value] of the field that showed [shownText]; returns the changed text, or null. */
    fun edit(value: TextFieldValue, shownText: String): String? {
        edited = value
        return value.text.takeIf { it != shownText }
    }

    /** Selects all of [text]. */
    fun selectAll(text: String) {
        edited = whole(text)
    }

    private fun whole(text: String) = TextFieldValue(text, TextRange(0, text.length))
}
