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
 * Hoisted state of the find/replace bar's fields (Compose-side helper state of the popup; the find logic is the
 * `FindModel`'s): the query field, the replacement field and [query], the query the bar shows (field text + toggles).
 * One instance per editor composition, so the replacement survives closing and reopening the bar.
 *
 * Synchronization with the model: `FindModel.state` publishes a query together with its matches, so it lags behind
 * `setQuery`. The bar therefore remembers the query it sent itself and *follows* the published query ([follow]) only
 * until the user edits the query or a toggle; every focus request ([takeFocusRequest]: `EditorOverlayState.openFind`,
 * which the editor may precede with `FindModel.setQuery` to seed the query from the selection) makes it follow again
 * and adopts the published query. So a result that lags behind the user's typing never reverts the field, and a query
 * the editor seeded reaches the field once it is published.
 *
 * @param published the model's published query when the bar's state is created.
 * @param focusRequest the value of `EditorOverlayState.findFocusRequests` at creation: requests up to it are handled
 *   (a bar composed again, e.g. after switching tabs, does not take focus from the text area).
 */
@Stable
internal class FindBarFields(published: FindQuery, focusRequest: Long) {

    /** The query the bar shows and last sent: the query field's text and the toggles. */
    var query: FindQuery by mutableStateOf(published)
        private set

    /** The query field (text, selection, IME composition). */
    var queryField: TextFieldValue by mutableStateOf(TextFieldValue(published.text))
        private set

    /** The replacement field (replace mode). */
    var replacementField: TextFieldValue by mutableStateOf(TextFieldValue())

    private var following = true
    private var handledFocusRequest = focusRequest

    /**
     * The user edited the query field to [value]; returns the query to send to the model, or null if only the selection
     * or composition changed. Stops following the published query.
     */
    fun editQuery(value: TextFieldValue): FindQuery? {
        queryField = value
        if (value.text == query.text) return null
        following = false
        query = query.copy(text = value.text)
        return query
    }

    /** The user switched [option]; returns the query to send. Stops following the published query. */
    fun toggle(option: FindOption): FindQuery {
        following = false
        query = option.toggle(query)
        return query
    }

    /** The model published [published]: adopted (text selected) while the bar follows the model. */
    fun follow(published: FindQuery) {
        if (following && published != query) adopt(published)
    }

    /**
     * Handles the focus request number [request] (the current `findFocusRequests`), with [published] the model's query
     * now. Returns false if that request was handled already; otherwise follows the model again, adopts [published],
     * selects the whole query and returns true (the caller then focuses the query field).
     */
    fun takeFocusRequest(request: Long, published: FindQuery): Boolean {
        if (request == handledFocusRequest) return false
        handledFocusRequest = request
        following = true
        if (published != query) adopt(published)
        queryField = queryField.copy(selection = TextRange(0, queryField.text.length))
        return true
    }

    private fun adopt(published: FindQuery) {
        query = published
        queryField = TextFieldValue(published.text, TextRange(0, published.text.length))
    }
}
