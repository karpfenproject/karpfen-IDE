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
package io.karpfen.ide.ui.editor.view

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.state.TestClipboard
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tab and Shift+Tab are the editor's indent keys and never move keyboard focus out of it, also when there is nothing to
 * indent or outdent: the editor sits between two focusable components, like the project tree and the tool windows in
 * the workbench.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorFocusTraversalTest {

    private fun ComposeUiTest.composeBetweenFocusables(text: String): CodeEditorState {
        lateinit var state: CodeEditorState
        setContent {
            val scope = rememberCoroutineScope()
            val editorState = remember {
                val session = DocumentSession(
                    DocumentId.of(Path.of(System.getProperty("java.io.tmpdir"), "focus.kstates")),
                    defaultLanguageRegistry().support(KarpfenLanguage.KSTATES),
                    EditableText(text),
                    scope,
                    DocumentSessionConfig(tokenizeDispatcher = Dispatchers.Unconfined, parseDispatcher = Dispatchers.Unconfined),
                )
                CodeEditorState(
                    session,
                    FakeLanguageServices(),
                    scope,
                    TestClipboard(),
                    keymap = defaultEditorKeymap(HostPlatform.MAC_OS),
                    backgroundDispatcher = Dispatchers.Unconfined,
                ).also { state = it }
            }
            Column {
                Box(Modifier.size(20.dp).testTag(BEFORE).focusable())
                Box(Modifier.size(400.dp, 200.dp)) { CodeEditor(editorState, Modifier.testTag(EditorTestTags.EDITOR)) }
                Box(Modifier.size(20.dp).testTag(AFTER).focusable())
            }
        }
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        waitForIdle()
        assertTrue(state.isFocused.value)
        return state
    }

    private fun ComposeUiTest.assertEditorKeepsFocus(state: CodeEditorState) {
        waitForIdle()
        assertTrue(state.isFocused.value, "the editor kept keyboard focus")
        onNodeWithTag(BEFORE).assertIsNotFocused()
        onNodeWithTag(AFTER).assertIsNotFocused()
    }

    @Test
    fun `Shift+Tab on a line without indentation keeps focus in the editor`() = runComposeUiTest {
        val state = composeBetweenFocusables("abc\ndef")

        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }

        assertEditorKeepsFocus(state)
        assertEquals("abc\ndef", state.session.text.snapshot.value.text)
    }

    @Test
    fun `Tab over a selection of empty lines keeps focus in the editor`() = runComposeUiTest {
        val state = composeBetweenFocusables("\n\n\nx")
        runOnIdle { state.caret.select(0, 2) }

        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { pressKey(Key.Tab) }

        assertEditorKeepsFocus(state)
        assertEquals("\n\n\nx", state.session.text.snapshot.value.text)
    }

    @Test
    fun `indenting keys that change the text keep focus too`() = runComposeUiTest {
        val state = composeBetweenFocusables("abc")

        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { pressKey(Key.Tab) }
        assertEditorKeepsFocus(state)
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        assertEditorKeepsFocus(state)

        assertEquals("abc", state.session.text.snapshot.value.text)
    }

    /** The test tags of the focusable neighbours. */
    private companion object {
        const val BEFORE = "focus.before"
        const val AFTER = "focus.after"
    }
}
