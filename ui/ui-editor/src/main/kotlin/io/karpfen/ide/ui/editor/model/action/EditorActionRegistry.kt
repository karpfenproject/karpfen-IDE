package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke

/** The key-bound and menu actions of an editor by id (spec §7.1). */
public interface EditorActionRegistry {

    /** All registered actions. */
    public val actions: Collection<EditorAction>

    /** The action [id], or null. */
    public fun get(id: String): EditorAction?

    /** Runs the action [id] in [context] if it is registered and enabled; returns whether it did something. */
    public fun execute(id: String, context: EditorActionContext): Boolean
}

/**
 * A registry of [actions].
 * @throws IllegalArgumentException if two actions share an id.
 */
public fun EditorActionRegistry(actions: Collection<EditorAction> = defaultEditorActions()): EditorActionRegistry =
    DefaultEditorActionRegistry(actions)

/**
 * Maps key strokes to action ids for one platform (spec §7.1, §7.2). Includes at least: caret movement with and without
 * Shift, word movement (Alt on macOS, Ctrl elsewhere), smart Home/End, Page Up/Down, document start/end, Select All,
 * Enter, Backspace, Delete, delete word, delete line (primary+Backspace on macOS, Ctrl+Y elsewhere), duplicate
 * (primary+D), move line (Alt+Shift+Up/Down), Tab/Shift+Tab, toggle comment (primary+/), copy/cut/paste, undo
 * (primary+Z), redo (primary+Shift+Z), find (primary+F), replace (primary+R), find next / previous (Cmd+G /
 * Shift+Cmd+G on macOS, F3 / Shift+F3 elsewhere), go to line (Cmd+L on macOS, Ctrl+G elsewhere), completion
 * (Ctrl+Space), go to definition (primary+B).
 *
 * Bindings are key strokes (`KeyStroke` equality per platform). Punctuation bindings such as primary+/ work on every
 * keyboard layout because the key adapter reports punctuation by the character it produces (`KeyCode` documents the
 * rule; on a German layout Cmd+Shift+7 arrives as Cmd+/), so no layout-specific second binding is needed.
 */
public interface EditorKeymap {

    /** The platform the bindings are for. */
    public val platform: HostPlatform

    /** The action id bound to [stroke], or null. */
    public fun actionFor(stroke: KeyStroke): String?

    /** The (first) shortcut bound to [actionId], for menus and tooltips; null if unbound. */
    public fun shortcutFor(actionId: String): KeyShortcut?
}

/** The default keymap of [platform] (bindings listed on [EditorKeymap]). */
public fun defaultEditorKeymap(platform: HostPlatform = HostPlatform.current()): EditorKeymap =
    DefaultEditorKeymap(platform)

/** Default [EditorActionRegistry]: the actions by id. */
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
 * Default [EditorKeymap]: the bindings of [defaultKeyBindings] for [platform], resolved to physical key strokes once.
 * Every stroke has at most one action (checked); [shortcutFor] answers an action's first binding.
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
