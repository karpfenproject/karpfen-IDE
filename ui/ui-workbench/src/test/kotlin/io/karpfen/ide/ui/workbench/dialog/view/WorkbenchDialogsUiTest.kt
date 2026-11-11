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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Headless tests of [WorkbenchDialogs]. They cover the buttons, the text field, Enter for the default button and Escape
 * for dismissing.
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
    fun `no request shows nothing`() = runComposeUiTest {
        setContent { KarpfenTheme(darkTheme = false) { WorkbenchDialogs(null) {} } }
        onNodeWithTag(WorkbenchDialogTestTags.DIALOG).assertDoesNotExist()
    }
}
