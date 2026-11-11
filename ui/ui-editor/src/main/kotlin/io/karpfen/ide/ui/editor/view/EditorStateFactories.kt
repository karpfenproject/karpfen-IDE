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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.editor.view.clipboard.LazySystemClipboard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Remembers a [CodeEditorState] for [session] in the composition. Its scope comes from `rememberCoroutineScope`. The
 * state is closed when it leaves the composition or when [session] changes. This is meant for simple embeddings and
 * tests. The workbench creates its states with the `CodeEditorState` factory, so they survive tab switches.
 */
@Composable
public fun rememberCodeEditorState(
    session: DocumentSession,
    languageServices: LanguageServices,
    settings: StateFlow<EditorSettings>? = null,
    clipboard: ClipboardPort = remember { systemClipboard() },
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
): CodeEditorState {
    val scope = rememberCoroutineScope()
    val state = remember(session, languageServices, settings, clipboard, backgroundDispatcher) {
        CodeEditorState(
            session = session,
            languageServices = languageServices,
            scope = scope,
            clipboard = clipboard,
            settings = settings ?: MutableStateFlow(EditorSettings()),
            backgroundDispatcher = backgroundDispatcher,
        )
    }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

/**
 * Creates the port to the system clipboard, which is the clipboard of the AWT `Toolkit`. Creating the port never
 * touches the toolkit. The platform clipboard is resolved on first use. When `GraphicsEnvironment.isHeadless()` is
 * true, as in headless tests and CI, the port falls back to an in-memory clipboard. So editors built with the default
 * workbench services never throw `HeadlessException`.
 */
public fun systemClipboard(): ClipboardPort = LazySystemClipboard()
