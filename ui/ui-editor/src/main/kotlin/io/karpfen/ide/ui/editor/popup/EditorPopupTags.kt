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

/**
 * Test tags of the editor popups (`Modifier.testTag`), so headless UI tests — the popups' own and the end-to-end tests
 * of the editor and the workbench — can find them. Stateless constants.
 */
public object EditorPopupTags {
    /** The find/replace bar. */
    public const val FIND_BAR: String = "editor.popup.findBar"

    /** The find bar's query field. */
    public const val FIND_QUERY: String = "editor.popup.findBar.query"

    /** The find bar's "n/m" counter (or the error of an invalid regular expression). */
    public const val FIND_STATUS: String = "editor.popup.findBar.status"

    /** The replace field (replace mode only). */
    public const val REPLACE_FIELD: String = "editor.popup.findBar.replacement"

    /** The completion list. */
    public const val COMPLETION: String = "editor.popup.completion"

    /** The go-to-line popup. */
    public const val GO_TO_LINE: String = "editor.popup.goToLine"

    /** The go-to-line input field. */
    public const val GO_TO_LINE_FIELD: String = "editor.popup.goToLine.field"

    /** The go-to-line hint (current line and line count). */
    public const val GO_TO_LINE_HINT: String = "editor.popup.goToLine.hint"

    /** The hover tooltip. */
    public const val HOVER: String = "editor.popup.hover"

    /** The context menu. */
    public const val CONTEXT_MENU: String = "editor.popup.contextMenu"

    /** Row [index] of the completion list (index into `CompletionUiState.items`). */
    public fun completionRow(index: Int): String = "editor.popup.completion.row.$index"
}
