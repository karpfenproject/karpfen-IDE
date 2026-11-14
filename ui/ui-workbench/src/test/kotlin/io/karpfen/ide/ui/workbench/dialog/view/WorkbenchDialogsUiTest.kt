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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.validation.ProjectValidation
import io.karpfen.ide.core.workspace.validation.ValidationFinding
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Headless tests of [WorkbenchDialogs]. They cover the buttons, the text field, Enter for the default button, Escape
 * for dismissing and the list of findings of a validation report.
 */
@OptIn(ExperimentalTestApi::class)
class WorkbenchDialogsUiTest {

    private val path: Path = Path.of("/ws/robot.kstates").toAbsolutePath()

    @Test
    fun `the rename dialog submits the edited name, a blank name cannot be submitted`() = runComposeUiTest {
        val answers = mutableListOf<DialogResponse>()
        setContent { KarpfenTheme(darkTheme = false) { WorkbenchDialogs(DialogRequest.Rename(path)) { answers.add(it) } } }

        onNodeWithText("New name for 'robot.kstates':").assertIsDisplayed()
        onNodeWithTag(WorkbenchDialogTestTags.INPUT).performTextReplacement(" ")
        onNodeWithTag(WorkbenchDialogTestTags.button("Rename")).assertIsNotEnabled()
        onNodeWithTag(WorkbenchDialogTestTags.INPUT).performTextReplacement("room.kstates")
        onNodeWithTag(WorkbenchDialogTestTags.INPUT).performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf<DialogResponse>(DialogResponse.Text("room.kstates")), answers)
    }

    @Test
    fun `buttons answer, Escape dismisses with the request's dismiss answer`() = runComposeUiTest {
        val answers = mutableListOf<DialogResponse>()
        setContent {
            KarpfenTheme(darkTheme = true) { WorkbenchDialogs(DialogRequest.UnsavedChanges(listOf(path))) { answers.add(it) } }
        }
        onNodeWithTag(WorkbenchDialogTestTags.button("Don't Save")).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.DIALOG).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(WorkbenchDialogTestTags.DIALOG).performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf<DialogResponse>(DialogResponse.Discard, DialogResponse.Cancel, DialogResponse.Save), answers.toList())
    }

    @Test
    fun `the run dialog starts with the remembered boxes ticked and answers with the ticked ones`() = runComposeUiTest {
        val answers = mutableListOf<DialogResponse>()
        val root = Path.of("/ws").toAbsolutePath()
        val bridges = listOf(root.resolve("http.kbridge.json"), root.resolve("lab/mqtt.kbridge.json"), root.resolve("ros.kbridge.json"))
        setContent {
            KarpfenTheme(darkTheme = false) {
                WorkbenchDialogs(DialogRequest.ConfigureRun(root, bridges, preselected = setOf(1), capture = true)) { answers.add(it) }
            }
        }
        onNodeWithText("http.kbridge.json").assertIsDisplayed()
        onNodeWithText("Capture run to file").assertIsDisplayed()
        onNodeWithTag(WorkbenchDialogTestTags.button("Run")).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.tick(0)).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.tick(1)).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.tick(2)).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.OPTION).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.DIALOG).performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(WorkbenchDialogTestTags.tick(0)).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.tick(2)).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.button("Run")).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.DIALOG).performKeyInput { pressKey(Key.Escape) }
        assertEquals(
            listOf(
                DialogResponse.RunWith(setOf(1), capture = true),
                DialogResponse.RunWith(setOf(0, 2), capture = false),
                DialogResponse.RunWith(emptySet(), capture = false),
                DialogResponse.Cancel,
            ),
            answers.toList(),
        )
    }

    @Test
    fun `the run dialog of a project without bridge files has only the capture box`() = runComposeUiTest {
        val answers = mutableListOf<DialogResponse>()
        val root = Path.of("/ws").toAbsolutePath()
        setContent {
            KarpfenTheme(darkTheme = false) { WorkbenchDialogs(DialogRequest.ConfigureRun(root, emptyList())) { answers.add(it) } }
        }
        onNodeWithTag(WorkbenchDialogTestTags.tick(0)).assertDoesNotExist()
        onNodeWithTag(WorkbenchDialogTestTags.OPTION).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.button("Run")).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.OPTION).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.button("Run")).performClick()
        assertEquals(
            listOf<DialogResponse>(DialogResponse.RunWith(emptySet(), capture = true), DialogResponse.RunWith(emptySet(), capture = false)),
            answers.toList(),
            "the first click after the dialog opened must tick the box",
        )
    }

    @Test
    fun `the validation report lists its findings and closes with its one button or with Escape`() = runComposeUiTest {
        val answers = mutableListOf<DialogResponse>()
        val root = Path.of("/ws").toAbsolutePath()
        val report = ProjectValidation(
            root,
            listOf(
                ValidationFinding(root.resolve("robot.kmeta"), 2, 1, Severity.ERROR, "syntax", "missing '}'"),
                ValidationFinding(root.resolve("robot.kstates"), 4, 16, Severity.WARNING, "project.unreachable-state", "lost"),
            ),
        )
        setContent {
            KarpfenTheme(darkTheme = false) { WorkbenchDialogs(DialogRequest.ValidationReport("Validate Project", report)) { answers.add(it) } }
        }
        onNodeWithText("Validate Project").assertIsDisplayed()
        onNodeWithText("1 error and 1 warning").assertIsDisplayed()
        onNodeWithTag(WorkbenchDialogTestTags.FINDINGS).assertIsDisplayed()
        onNodeWithTag(WorkbenchDialogTestTags.finding(0)).assertTextEquals("robot.kmeta:2  missing '}'")
        onNodeWithTag(WorkbenchDialogTestTags.finding(1)).assertTextEquals("robot.kstates:4  lost")
        onNodeWithTag(WorkbenchDialogTestTags.finding(1)).assertHasNoClickAction()
        onNodeWithTag(WorkbenchDialogTestTags.button("Close")).performClick()
        onNodeWithTag(WorkbenchDialogTestTags.DIALOG).performKeyInput { pressKey(Key.Escape) }
        assertEquals(listOf<DialogResponse>(DialogResponse.Confirm, DialogResponse.Confirm), answers.toList())
    }

    @Test
    fun `no request shows nothing`() = runComposeUiTest {
        setContent { KarpfenTheme(darkTheme = false) { WorkbenchDialogs(null) {} } }
        onNodeWithTag(WorkbenchDialogTestTags.DIALOG).assertDoesNotExist()
    }
}
