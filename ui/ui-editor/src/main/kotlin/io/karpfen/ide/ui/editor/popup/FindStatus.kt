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

import io.karpfen.ide.ui.editor.model.find.FindResult

/**
 * The status shown next to the toggles of the find bar. It is a plain value. It holds the "n/m" counter, the message of
 * an invalid regular expression, or nothing for an empty query. [isError] marks texts shown in the error colour, which
 * are the regex error and "0/0" for no match.
 */
internal data class FindStatus(val text: String, val isError: Boolean) {

    /** Derives the status from a find result. */
    companion object {
        /** Returns the status of [result]. */
        fun of(result: FindResult): FindStatus = when {
            result.error != null -> FindStatus(result.error, isError = true)
            result.query.text.isEmpty() -> FindStatus("", isError = false)
            else -> FindStatus("${result.currentNumber}/${result.total}", isError = result.total == 0)
        }
    }
}
