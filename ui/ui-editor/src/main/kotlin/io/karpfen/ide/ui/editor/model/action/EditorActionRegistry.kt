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
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke

/** The actions of an editor that keys and menus run, looked up by id. */
public interface EditorActionRegistry {

    /** All registered actions. */
    public val actions: Collection<EditorAction>

    /** Returns the action with [id], or null if there is none. */
    public fun get(id: String): EditorAction?

    /** Runs the action with [id] in [context] if it is registered and enabled. Returns whether it did something. */
    public fun execute(id: String, context: EditorActionContext): Boolean
}

/**
 * Creates a registry of [actions]. By default these are all actions of [defaultEditorActions].
 *
 * @throws IllegalArgumentException if two actions share an id.
 */
public fun EditorActionRegistry(actions: Collection<EditorAction> = defaultEditorActions()): EditorActionRegistry =
    DefaultEditorActionRegistry(actions)

/**
 * Maps key strokes to action ids for one platform. "Primary" below means Cmd on macOS and Ctrl elsewhere.
 *
 * The keymap covers at least these bindings. Caret movement works with and without Shift. Word movement uses Alt on
 * macOS and Ctrl elsewhere. There are smart Home and End, Page Up and Page Down, document start and end, Select All,
 * Enter, Backspace, Delete and delete word. Delete line is primary+Backspace on macOS and Ctrl+Y elsewhere. Duplicate
 * is primary+D. Move line is Alt+Shift+Up and Alt+Shift+Down. Tab indents and Shift+Tab outdents. Toggle comment is
 * primary+/. Copy, cut and paste use the usual keys. Undo is primary+Z and redo is primary+Shift+Z. Find is primary+F
 * and replace is primary+R. Find next and find previous are Cmd+G and Shift+Cmd+G on macOS, and F3 and Shift+F3
 * elsewhere. Go to line is Cmd+L on macOS and Ctrl+G elsewhere. Completion is Ctrl+M on every platform, and on Windows
 * also Ctrl+Space. Go to definition is primary+B.
 *
 * Bindings are key strokes, compared with `KeyStroke` equality per platform. Punctuation bindings such as primary+/
 * work on every keyboard layout, because the key adapter reports a punctuation key by the character it produces. On a
 * German layout, for example, Cmd+Shift+7 arrives as Cmd+/. `KeyCode` documents this rule. So no second binding per
 * layout is needed.
 */
public interface EditorKeymap {

    /** The platform the bindings are for. */
    public val platform: HostPlatform

    /** Returns the action id bound to [stroke], or null if the stroke is unbound. */
    public fun actionFor(stroke: KeyStroke): String?

    /** Returns the first shortcut bound to [actionId], for menus and tooltips, or null if the action is unbound. */
    public fun shortcutFor(actionId: String): KeyShortcut?
}

/** Creates the default keymap of [platform], with the bindings listed on [EditorKeymap]. */
public fun defaultEditorKeymap(platform: HostPlatform = HostPlatform.current()): EditorKeymap =
    DefaultEditorKeymap(platform)

/** The default [EditorActionRegistry]. It indexes the actions by id and rejects duplicate ids. */
internal class DefaultEditorActionRegistry(actions: Collection<EditorAction>) : EditorActionRegistry {
    override val actions: Collection<EditorAction> = actions

    private val byId: Map<String, EditorAction> = actions.associateBy(EditorAction::id)

    init {
        val duplicates = actions.groupBy(EditorAction::id).filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "editor action ids must be unique, duplicated: $duplicates" }
    }

    override fun get(id: String): EditorAction? = byId[id]

    override fun execute(id: String, context: EditorActionContext): Boolean {
        val action = byId[id] ?: return false
        return action.isEnabled(context) && action.execute(context)
    }
}

/**
 * The default [EditorKeymap]. It resolves the bindings of [defaultKeyBindings] for [platform] to physical key strokes
 * once. The constructor checks that every stroke has at most one action. [shortcutFor] answers the first binding of an
 * action.
 */
internal class DefaultEditorKeymap(override val platform: HostPlatform) : EditorKeymap {

    private val bindings: List<KeyBinding> = defaultKeyBindings(platform)

    private val actionByStroke: Map<KeyStroke, String> = bindings.associate { it.shortcut.strokeFor(platform) to it.actionId }

    private val shortcutByAction: Map<String, KeyShortcut> =
        bindings.groupBy(KeyBinding::actionId).mapValues { (_, bound) -> bound.first().shortcut }

    init {
        val strokes = bindings.groupBy { it.shortcut.strokeFor(platform) }.filterValues { it.size > 1 }
        require(strokes.isEmpty()) { "key strokes bound twice on $platform: ${strokes.keys}" }
    }

    override fun actionFor(stroke: KeyStroke): String? = actionByStroke[stroke]

    override fun shortcutFor(actionId: String): KeyShortcut? = shortcutByAction[actionId]
}
