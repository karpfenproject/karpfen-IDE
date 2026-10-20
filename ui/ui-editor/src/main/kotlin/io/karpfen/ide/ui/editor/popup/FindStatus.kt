package io.karpfen.ide.ui.editor.popup

import io.karpfen.ide.ui.editor.model.find.FindResult

/**
 * The status shown next to the find bar's toggles (pure value): the "n/m" counter (spec §7.2), the message of an
 * invalid regular expression, or nothing for an empty query. [isError] marks texts shown in the error colour: the
 * regex error and "0/0" (no match).
 */
internal data class FindStatus(val text: String, val isError: Boolean) {

    companion object {
        /** The status of [result]. */
        fun of(result: FindResult): FindStatus = when {
            result.error != null -> FindStatus(result.error, isError = true)
            result.query.text.isEmpty() -> FindStatus("", isError = false)
            else -> FindStatus("${result.currentNumber}/${result.total}", isError = result.total == 0)
        }
    }
}
