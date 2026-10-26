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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.state.TestClipboard
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless keyboard tests of `CodeEditor`: typed text through `performTextInput`
 * (`SemanticsActions.InsertTextAtCursor`), key strokes through the keymap, selection by keys, undo, focus, and the
 * consumption rule (handled strokes are consumed, unhandled ones reach the parents, where menu accelerators listen).
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorKeyboardTest {

    @Test
    fun `typed text is inserted at the caret`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(" hello")

        ui.editor.performTextInput("ab")
        waitForIdle()
        ui.editor.performTextInput("{")
        waitForIdle()

        assertEquals("ab{} hello", ui.plain, "typing goes through the typing action (auto-close before a space)")
        assertEquals(3, ui.state.caret.primary.position)
        assertTrue(ui.state.isFocused.value, "text input focuses the editor")
        ui.editor.assertIsFocused()
    }

    @Test
    fun `keys move the caret, select, delete and undo`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose("one two")
        ui.editor.requestFocus()

        ui.editor.performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) }
        }
        waitForIdle()
        assertEquals(TextRange(2, 3), ui.state.caret.primary.selection)

        ui.editor.performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals("on two", ui.plain)

        ui.editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals("one two", ui.plain)
        assertEquals(TextRange(2, 3), ui.state.caret.primary.selection, "undo restores the carets")
    }

    @Test
    fun `typing and key commands keep their order`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose("")

        ui.editor.performTextInput("ab")
        ui.editor.performKeyInput { pressKey(Key.DirectionLeft) }
        ui.editor.performTextInput("X")
        waitForIdle()

        assertEquals("aXb", ui.plain)
    }

    @Test
    fun `a handled stroke is consumed, an unhandled one reaches the parents`() = runComposeUiTest {
        val reached = mutableListOf<Key>()
        lateinit var state: CodeEditorState
        setContent {
            val scope = rememberCoroutineScope()
            val editorState = remember {
                val session = DocumentSession(
                    DocumentId.of(Path.of(System.getProperty("java.io.tmpdir"), "keys.kstates")),
                    defaultLanguageRegistry().support(KarpfenLanguage.KSTATES),
                    EditableText("abc"),
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
            Box(Modifier.size(400.dp, 200.dp).onKeyEvent { if (it.type == KeyEventType.KeyDown) reached += it.key; false }) {
                CodeEditor(editorState, Modifier.testTag(EditorTestTags.EDITOR))
            }
        }
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()

        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.F12)
            withKeyDown(Key.MetaLeft) { pressKey(Key.S) }
        }
        waitForIdle()

        assertEquals(1, state.caret.primary.position)
        assertTrue(Key.DirectionRight !in reached, "the editor handled Right: $reached")
        assertTrue(Key.F12 in reached, "nobody handled F12: $reached")
        assertTrue(Key.S in reached, "Cmd+S belongs to the window menu: $reached")
    }

    @Test
    fun `the keymap's commands run from keys, like toggle comment and find`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose("STATE")
        ui.editor.requestFocus()

        ui.editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Slash) } }
        waitForIdle()
        assertEquals("// STATE", ui.plain)

        ui.editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.F) } }
        waitForIdle()
        assertEquals(FindBarMode.FIND, ui.state.overlays.findBar.value)
    }

    @Test
    fun `focus changes reach the state`() = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose("abc")
        assertEquals(false, ui.state.isFocused.value)

        ui.editor.requestFocus()
        waitForIdle()

        assertTrue(ui.state.isFocused.value)
    }
}
