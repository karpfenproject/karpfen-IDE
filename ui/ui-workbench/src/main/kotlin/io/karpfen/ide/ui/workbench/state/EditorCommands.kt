package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the active editor allows the menu to run (decisions 15 and 36 of the work-package plan): the ids of its enabled
 * actions and whether it has keyboard focus.
 */
internal data class EditorCommandState(val enabled: Set<String>, val focused: Boolean) {

    /**
     * True if the editor command [id] may run: it is enabled, and the editor is focused unless the command opens a popup
     * or navigates ([FOCUS_INDEPENDENT]; those request focus for the editor first).
     */
    fun allows(id: String): Boolean = id in enabled && (focused || id in FOCUS_INDEPENDENT)

    companion object {
        /** No active editor. */
        val NONE: EditorCommandState = EditorCommandState(emptySet(), focused = false)

        /** Editor commands that run while the editor is not focused. */
        val FOCUS_INDEPENDENT: Set<String> = setOf(
            EditorActionIds.FIND, EditorActionIds.REPLACE, EditorActionIds.FIND_NEXT, EditorActionIds.FIND_PREVIOUS,
            EditorActionIds.GO_TO_LINE, EditorActionIds.GO_TO_DEFINITION,
        )

        /** Ids that are no menu commands (typed text arrives through text input). */
        val NOT_COMMANDS: Set<String> = setOf(EditorActionIds.TYPE_TEXT)
    }
}

/**
 * Registers the editor commands of the window menu (spec §9): every editor action under its `EditorActionIds` id with
 * its `EditorActionTitles` title and the shortcut of the workbench's one [keymap] (decision 15), enabled by
 * [EditorCommandState.allows] for the active editor and forwarded to it (`EditorHandle.execute`).
 */
internal class EditorCommands(
    private val scope: CoroutineScope,
    private val activeEditor: StateFlow<CodeEditorState?>,
    private val keymap: EditorKeymap,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val state: StateFlow<EditorCommandState> = activeEditor
        .flatMapLatest { editor ->
            if (editor == null) flowOf(EditorCommandState.NONE) else combine(editor.enabledActions, editor.isFocused, ::EditorCommandState)
        }
        .stateIn(scope, SharingStarted.Eagerly, EditorCommandState.NONE)

    fun register(registry: CommandRegistry) {
        EditorActionTitles.titles.keys.filterNot { it in EditorCommandState.NOT_COMMANDS }.forEach { id ->
            registry.register(
                Command(
                    id = id,
                    title = EditorActionTitles.of(id),
                    shortcut = keymap.shortcutFor(id),
                    isEnabled = state.map { it.allows(id) }.stateIn(scope, SharingStarted.Eagerly, state.value.allows(id)),
                    action = { run(id) },
                ),
            )
        }
    }

    private fun run(id: String) {
        val editor = activeEditor.value ?: return
        val current = EditorCommandState(editor.enabledActions.value, editor.isFocused.value)
        if (!current.allows(id)) return
        if (id in EditorCommandState.FOCUS_INDEPENDENT) editor.requestFocus()
        editor.execute(id)
    }
}
