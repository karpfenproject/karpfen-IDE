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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.karpfen.ide.ui.editor.model.find.FindQuery

/** The toggles of the find bar, labelled `Aa`, `W` and `.*`. Each one switches one flag of [FindQuery]. */
internal enum class FindOption(
    /** The label of the toggle. */
    val label: String,
    /** The accessible description of the toggle. Tests also find the toggle by this name. */
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

    /** Returns [query] with this option switched. */
    fun toggle(query: FindQuery): FindQuery = when (this) {
        MATCH_CASE -> query.copy(caseSensitive = !query.caseSensitive)
        WHOLE_WORD -> query.copy(wholeWord = !query.wholeWord)
        REGEX -> query.copy(regex = !query.regex)
    }
}

/**
 * The hoisted state of the text fields of the find and replace bar. It holds the selection and the input method
 * composition of the query field and the replacement field. That is view state, kept on the Compose side of the popup.
 * The texts themselves belong to the `FindModel`, as its [query][io.karpfen.ide.ui.editor.model.find.FindModel.query]
 * and [replacement][io.karpfen.ide.ui.editor.model.find.FindModel.replacement], and the bar renders them. There is one
 * instance per editor composition. A new composition, for example after switching tabs, starts with fresh selections
 * over the texts of the model.
 *
 * Both fields always show the text of the model. While a field shows that text, it keeps its value as the user edited
 * it. A text that the editor set, such as a query seeded from the selection, is shown selected as a whole.
 *
 * @param focusRequest the value of `EditorOverlayState.findFocusRequests` at creation. Requests up to this value count
 *   as handled. So a bar that is composed again, for example after switching tabs, does not take focus from the text
 *   area.
 */
@Stable
internal class FindBarFields(focusRequest: Long) {

    private val query = ModelTextField()
    private val replacement = ModelTextField()
    private var handledFocusRequest = focusRequest

    /** Returns the query field showing the model's query [text]. */
    fun queryField(text: String): TextFieldValue = query.value(text)

    /**
     * Records that the user edited the query field, which showed [shownText], to [value]. Returns the new query text to
     * send to the model, or null if only the selection or composition changed.
     */
    fun editQuery(value: TextFieldValue, shownText: String): String? = query.edit(value, shownText)

    /** Returns the replacement field showing the model's replacement [text]. */
    fun replacementField(text: String): TextFieldValue = replacement.value(text)

    /** Works like [editQuery] for the replacement field. Returns the new replacement to send to the model, or null. */
    fun editReplacement(value: TextFieldValue, shownText: String): String? = replacement.edit(value, shownText)

    /**
     * Handles the focus request number [request], which is the current value of `findFocusRequests`, for the model's
     * query [text]. Returns false if that request was handled already. Otherwise it selects the whole query and returns
     * true, and the caller then focuses the query field.
     */
    fun takeFocusRequest(request: Long, text: String): Boolean {
        if (request == handledFocusRequest) return false
        handledFocusRequest = request
        query.selectAll(text)
        return true
    }
}

/**
 * One text field over a text that the model owns. It keeps the field as it was last edited or selected, with text,
 * selection and input method composition. That value is shown while it holds the model's text. Any other text of the
 * model is shown selected as a whole.
 */
@Stable
private class ModelTextField {
    private var edited: TextFieldValue by mutableStateOf(TextFieldValue())

    /** Returns the field showing the model's [text]. */
    fun value(text: String): TextFieldValue = if (edited.text == text) edited else whole(text)

    /** Records the user's [value] of the field that showed [shownText]. Returns the changed text, or null if the text is unchanged. */
    fun edit(value: TextFieldValue, shownText: String): String? {
        edited = value
        return value.text.takeIf { it != shownText }
    }

    /** Selects all of [text]. */
    fun selectAll(text: String) {
        edited = whole(text)
    }

    /** Returns [text] selected as a whole. */
    private fun whole(text: String) = TextFieldValue(text, TextRange(0, text.length))
}
