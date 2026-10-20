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
 * Remembers a [CodeEditorState] for [session] in the composition (scope from `rememberCoroutineScope`, closed when it
 * leaves the composition or [session] changes). For simple embeddings and tests; the workbench creates states with
 * the `CodeEditorState` factory so they survive tab switches.
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
 * The system clipboard (AWT `Toolkit` clipboard or the non-deprecated Compose clipboard API). Creating the port never
 * touches the toolkit: the platform clipboard is resolved lazily on first use, and when
 * `GraphicsEnvironment.isHeadless()` (headless tests, CI) the port degrades to an in-memory clipboard, so editors built
 * with the default workbench services never throw `HeadlessException`.
 */
public fun systemClipboard(): ClipboardPort = LazySystemClipboard()
