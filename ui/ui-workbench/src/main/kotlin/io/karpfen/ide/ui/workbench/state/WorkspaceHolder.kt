package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.workspace.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current [Workspace] of the workbench with its [LanguageServices] and the window title derived from it
 * (`<folder> — karpfen IDE`). A plain holder; opening and closing workspaces is the folder flow's job.
 */
internal class WorkspaceHolder {

    private val current = MutableStateFlow<Workspace?>(null)
    private val services = MutableStateFlow<LanguageServices?>(null)
    private val title = MutableStateFlow(APPLICATION_NAME)

    /** The opened folder's workspace, or null. */
    val workspace: StateFlow<Workspace?> get() = current.asStateFlow()

    /** The language services of [workspace], or null. */
    val languageServices: StateFlow<LanguageServices?> get() = services.asStateFlow()

    /** The window title. */
    val windowTitle: StateFlow<String> get() = title.asStateFlow()

    /** Holds [workspace] and its [languageServices]. */
    fun set(workspace: Workspace, languageServices: LanguageServices) {
        current.value = workspace
        services.value = languageServices
        title.value = "${workspace.root.fileName ?: workspace.root} — $APPLICATION_NAME"
    }

    /** Forgets the current workspace and returns it (the caller closes it), or null. */
    fun clear(): Workspace? {
        val previous = current.value
        current.value = null
        services.value = null
        title.value = APPLICATION_NAME
        return previous
    }

    companion object {
        /** The application's name, the window title without a folder. */
        const val APPLICATION_NAME: String = "karpfen IDE"
    }
}
