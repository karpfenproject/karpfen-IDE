package io.karpfen.ide.core.text

/**
 * The text as the [DefaultUndoManager] sees it: undo and redo apply their edits through this port, bypassing the
 * recording of history, and publish the change once the history is consistent again.
 */
internal interface EditTarget {

    /**
     * Applies [edits] (coordinates of the current version) as one new version with [origin] without recording undo
     * history, and queues the change for publication; null if the edits change nothing.
     */
    fun commit(edits: List<TextEdit>, origin: EditOrigin): TextChange?

    /** Publishes every committed but unpublished change to the text's observers (snapshot and change flows). */
    fun publishPending()
}
