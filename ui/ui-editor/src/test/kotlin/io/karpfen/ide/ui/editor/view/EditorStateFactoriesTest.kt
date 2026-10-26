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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.state.TestClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** `rememberCodeEditorState`: one state per session in the composition, closed when it leaves. */
@OptIn(ExperimentalTestApi::class)
class EditorStateFactoriesTest {

    @Test
    fun `the remembered state survives recomposition and is closed when it leaves the composition`() = runComposeUiTest {
        var shown by mutableStateOf(true)
        var tick by mutableStateOf(0)
        val states = mutableListOf<CodeEditorState>()
        val settings = MutableStateFlow(EditorSettings(fontSize = 15f))
        val services = FakeLanguageServices()
        val clipboard = TestClipboard()
        setContent {
            val scope = rememberCoroutineScope()
            val session = remember {
                DocumentSession(
                    DocumentId.of(Path.of(System.getProperty("java.io.tmpdir"), "remember.kstates")),
                    defaultLanguageRegistry().support(KarpfenLanguage.KSTATES),
                    EditableText("abc"),
                    scope,
                    DocumentSessionConfig(tokenizeDispatcher = Dispatchers.Unconfined, parseDispatcher = Dispatchers.Unconfined),
                )
            }
            if (shown) {
                val state = rememberCodeEditorState(session, services, settings, clipboard, Dispatchers.Unconfined)
                states += state
                tick.let { CodeEditor(state) }
            }
        }
        waitForIdle()
        tick++
        waitForIdle()
        val state = states.first()
        assertSame(state, states.last(), "the same state after recomposition")
        assertEquals(15f, state.settings.value.fontSize, "the given settings")
        state.overlays.openFind(FindBarMode.FIND)

        shown = false
        waitForIdle()

        assertNull(state.overlays.findBar.value, "closed with the composition")
    }
}
