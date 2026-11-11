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
 * The test tags of the editor popups, set with `Modifier.testTag`. Headless UI tests use them to find the popups. These
 * are the tests of the popups themselves and the end-to-end tests of the editor and the workbench. The object holds
 * constants only.
 */
public object EditorPopupTags {
    /** The find and replace bar. */
    public const val FIND_BAR: String = "editor.popup.findBar"

    /** The query field of the find bar. */
    public const val FIND_QUERY: String = "editor.popup.findBar.query"

    /** The "n/m" counter of the find bar. It shows the error of an invalid regular expression instead when there is one. */
    public const val FIND_STATUS: String = "editor.popup.findBar.status"

    /** The replace field. It exists only in replace mode. */
    public const val REPLACE_FIELD: String = "editor.popup.findBar.replacement"

    /** The completion list. */
    public const val COMPLETION: String = "editor.popup.completion"

    /** The go-to-line popup. */
    public const val GO_TO_LINE: String = "editor.popup.goToLine"

    /** The go-to-line input field. */
    public const val GO_TO_LINE_FIELD: String = "editor.popup.goToLine.field"

    /** The go-to-line hint, which shows the current line and the line count. */
    public const val GO_TO_LINE_HINT: String = "editor.popup.goToLine.hint"

    /** The hover tooltip. */
    public const val HOVER: String = "editor.popup.hover"

    /** The context menu. */
    public const val CONTEXT_MENU: String = "editor.popup.contextMenu"

    /** Returns the tag of row [index] of the completion list. [index] is an index into `CompletionUiState.items`. */
    public fun completionRow(index: Int): String = "editor.popup.completion.row.$index"
}
