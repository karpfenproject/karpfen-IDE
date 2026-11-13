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
package io.karpfen.ide.ui.workbench.validation

/**
 * What the Validate button below the editor shows, taken from a [ValidationState].
 *
 * @property enabled true if a click starts a validation, which needs an open folder and no validation running.
 * @property busy true while a validation runs. The button then says so in its [label].
 */
public data class ValidateButtonUiState(public val enabled: Boolean = false, public val busy: Boolean = false) {

    /** The text of the button: [IDLE] or, while a validation runs, [BUSY]. */
    public val label: String get() = if (busy) BUSY else IDLE

    public companion object {
        /** The text of the button while no validation runs. */
        public const val IDLE: String = "Validate"

        /** The text of the button while a validation runs. */
        public const val BUSY: String = "Validating…"
    }
}
