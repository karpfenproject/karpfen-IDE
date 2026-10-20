package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.StateFlow

/**
 * Default [UndoManager] with transactions (groups), coalescing of typing and caret mementos (Memento pattern).
 * Owned by a [DefaultEditableText], which reports every applied change and applies the inverse edits for undo/redo.
 */
internal class DefaultUndoManager : UndoManager {

    override val canUndo: StateFlow<Boolean>
        get() = TODO("WP-TEXT: canUndo")

    override val canRedo: StateFlow<Boolean>
        get() = TODO("WP-TEXT: canRedo")

    override val isAtSavePoint: StateFlow<Boolean>
        get() = TODO("WP-TEXT: save point tracking")

    override fun undo(): UndoResult? = TODO("WP-TEXT: undo")

    override fun redo(): UndoResult? = TODO("WP-TEXT: redo")

    override fun <T> group(name: String, block: () -> T): T = TODO("WP-TEXT: undo groups")

    override fun breakCoalescing(): Unit = TODO("WP-TEXT: break coalescing")

    override fun markSavePoint(): Unit = TODO("WP-TEXT: mark save point")

    override fun clear(): Unit = TODO("WP-TEXT: clear history")
}
