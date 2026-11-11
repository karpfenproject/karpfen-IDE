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
 * What the active editor allows the menu to run. It holds the ids of the editor's enabled actions and whether the
 * editor has the keyboard focus. Text-editing commands from the menu act only on a focused editor. Cut or Undo chosen
 * while the Project tree has the focus therefore never changes the editor's text.
 */
internal data class EditorCommandState(val enabled: Set<String>, val focused: Boolean) {

    /**
     * True if the editor command [id] may run. The command must be enabled. The editor must also be focused, unless the
     * command opens a popup or navigates. Those commands are listed in [FOCUS_INDEPENDENT], and they request the focus
     * for the editor first, as [focusesEditor] describes.
     */
    fun allows(id: String): Boolean = id in enabled && (focused || id in FOCUS_INDEPENDENT)

    companion object {
        /** The state when there is no active editor. */
        val NONE: EditorCommandState = EditorCommandState(emptySet(), focused = false)

        /** Editor commands that run while the editor is not focused. */
        val FOCUS_INDEPENDENT: Set<String> = setOf(
            EditorActionIds.FIND, EditorActionIds.REPLACE, EditorActionIds.FIND_NEXT, EditorActionIds.FIND_PREVIOUS,
            EditorActionIds.GO_TO_LINE, EditorActionIds.GO_TO_DEFINITION,
        )

        /** The find navigation commands, which step through the matches of the find bar's query. */
        val FIND_STEPS: Set<String> = setOf(EditorActionIds.FIND_NEXT, EditorActionIds.FIND_PREVIOUS)

        /**
         * True if running [id] from the menu first requests the focus for the editor. The [FOCUS_INDEPENDENT] commands
         * do that, with one exception. Find next and find previous leave the focus alone while the editor's find bar is
         * open, as [findBarOpen] says. Pressed in the bar's query field with Cmd+G or F3, they step through the matches
         * and keep the focus in the field. Typing then refines the query instead of editing the document.
         */
        fun focusesEditor(id: String, findBarOpen: Boolean): Boolean =
            id in FOCUS_INDEPENDENT && !(findBarOpen && id in FIND_STEPS)

        /** Ids that are not menu commands. Typed text, for example, arrives through text input. */
        val NOT_COMMANDS: Set<String> = setOf(EditorActionIds.TYPE_TEXT)
    }
}

/**
 * Registers the editor commands of the window menu. Every editor action is registered under its `EditorActionIds` id,
 * with its `EditorActionTitles` title and its shortcut from the workbench's single [keymap]. Every editor is created
 * with the same keymap, so the menu and the editors bind the same keystrokes.
 *
 * A command is enabled when [EditorCommandState.allows] says so for the active editor. Running it forwards it to that
 * editor through `EditorHandle.execute`. Before that, the command requests the editor's focus where
 * [EditorCommandState.focusesEditor] says so.
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

    /**
     * Registers one command per editor action in [registry]. Typed text is left out, because it is not a menu command.
     */
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

    /**
     * Runs [id] on the active editor if the editor still allows it. Requests the editor's focus first where that is
     * due.
     */
    private fun run(id: String) {
        val editor = activeEditor.value ?: return
        val current = EditorCommandState(editor.enabledActions.value, editor.isFocused.value)
        if (!current.allows(id)) return
        if (EditorCommandState.focusesEditor(id, findBarOpen = editor.overlays.findBar.value != null)) editor.requestFocus()
        editor.execute(id)
    }
}
