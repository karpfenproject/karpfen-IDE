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
package io.karpfen.ide.ui.workbench.dialog.view

/** Test tags of the modal dialog that [WorkbenchDialogs] renders. */
public object WorkbenchDialogTestTags {
    /** The dialog. */
    public const val DIALOG: String = "Workbench.dialog"

    /** The dialog's text field, as shown by the rename and new file dialogs. */
    public const val INPUT: String = "Workbench.dialog.input"

    /** The entry at [index] of the dialog's list, as shown when the user picks the descriptor to run. */
    public fun choice(index: Int): String = "Workbench.dialog.choice:$index"

    /** The box at [index] of the dialog's list of boxes, as shown when the user ticks the bridges to start. */
    public fun tick(index: Int): String = "Workbench.dialog.tick:$index"

    /** The single box below the list of boxes, as shown when the user chooses whether to capture a run to a file. */
    public const val OPTION: String = "Workbench.dialog.option"

    /** The box below [OPTION], as shown when the user chooses whether parallel branches step on threads of their own. */
    public const val SECOND_OPTION: String = "Workbench.dialog.secondOption"

    /** The list of findings, as shown by the report of a validation. */
    public const val FINDINGS: String = "Workbench.dialog.findings"

    /** The row at [index] of the list of findings. */
    public fun finding(index: Int): String = "Workbench.dialog.finding:$index"

    /** The dialog's button with the text [label]. */
    public fun button(label: String): String = "Workbench.dialog.button:$label"
}
