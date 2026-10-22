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
 * What the active editor allows the menu to run: the ids of its enabled actions and whether it has keyboard focus.
 * Text-editing commands from the menu act only on a focused editor, so Cut or Undo chosen while the Project tree has
 * the focus never changes the editor's text.
 */
internal data class EditorCommandState(val enabled: Set<String>, val focused: Boolean) {

    /**
     * True if the editor command [id] may run: it is enabled, and the editor is focused unless the command opens a popup
     * or navigates ([FOCUS_INDEPENDENT]; those request focus for the editor first, see [focusesEditor]).
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

        /** Find navigation: steps through the matches of the find bar's query. */
        val FIND_STEPS: Set<String> = setOf(EditorActionIds.FIND_NEXT, EditorActionIds.FIND_PREVIOUS)

        /**
         * True if running [id] from the menu first requests focus for the editor: the [FOCUS_INDEPENDENT] commands do,
         * except find next/previous while the editor's find bar is open ([findBarOpen]) — typed into the bar's query
         * field (Cmd+G, F3), they step through the matches and leave the focus there, so typing refines the query
         * instead of editing the document.
         */
        fun focusesEditor(id: String, findBarOpen: Boolean): Boolean =
            id in FOCUS_INDEPENDENT && !(findBarOpen && id in FIND_STEPS)

        /** Ids that are no menu commands (typed text arrives through text input). */
        val NOT_COMMANDS: Set<String> = setOf(EditorActionIds.TYPE_TEXT)
    }
}

/**
 * Registers the editor commands of the window menu: every editor action under its `EditorActionIds` id with its
 * `EditorActionTitles` title and the shortcut of the workbench's one [keymap] (the keymap every editor is created with,
 * so the menu and the editors bind the same strokes), enabled by [EditorCommandState.allows] for the active editor and
 * forwarded to it (`EditorHandle.execute`), after requesting its focus where [EditorCommandState.focusesEditor] says
 * so.
 */
internal class EditorCommands(
    private val scope: CoroutineScope,
    private val activeEditor: StateFlow<CodeEditorState?>,
    private val keymap: EditorKeymap,
) {

    /** The command state of whichever editor is active. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val state: StateFlow<EditorCommandState> = activeEditor
        .flatMapLatest { editor ->
            if (editor == null) flowOf(EditorCommandState.NONE) else combine(editor.enabledActions, editor.isFocused, ::EditorCommandState)
        }
        .stateIn(scope, SharingStarted.Eagerly, EditorCommandState.NONE)

    /** Registers one command per editor action in [registry], except typed text, which is no menu command. */
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

    /** Runs [id] on the active editor if it still allows it, first requesting the editor's focus where that is due. */
    private fun run(id: String) {
        val editor = activeEditor.value ?: return
        val current = EditorCommandState(editor.enabledActions.value, editor.isFocused.value)
        if (!current.allows(id)) return
        if (EditorCommandState.focusesEditor(id, findBarOpen = editor.overlays.findBar.value != null)) editor.requestFocus()
        editor.execute(id)
    }
}
