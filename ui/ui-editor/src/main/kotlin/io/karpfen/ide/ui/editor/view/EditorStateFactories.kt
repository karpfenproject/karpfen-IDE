package io.karpfen.ide.ui.editor.view

import androidx.compose.runtime.Composable
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Remembers a [CodeEditorState] for [session] in the composition (scope from `rememberCoroutineScope`, closed when it
 * leaves the composition or [session] changes). For simple embeddings and tests; the workbench creates states with
 * the `CodeEditorState` factory so they survive tab switches.
 */
@Composable
public fun rememberCodeEditorState(
    session: DocumentSession,
    services: LanguageServices,
    settings: StateFlow<EditorSettings>? = null,
    clipboard: ClipboardPort = systemClipboard(),
): CodeEditorState = TODO("WP-EDITOR-VIEW: remember the editor state")

/** The system clipboard (AWT `Toolkit` clipboard or the non-deprecated Compose clipboard API). */
public fun systemClipboard(): ClipboardPort = TODO("WP-EDITOR-VIEW: system clipboard port")
