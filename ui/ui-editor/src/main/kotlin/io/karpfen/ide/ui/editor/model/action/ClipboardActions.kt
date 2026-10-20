package io.karpfen.ide.ui.editor.model.action

/** Kinds of [ClipboardAction]. */
internal enum class ClipboardOperation(val actionId: String) {
    COPY(EditorActionIds.COPY),
    CUT(EditorActionIds.CUT),
    PASTE(EditorActionIds.PASTE),
}

/** Copy/cut (whole line without selection) and paste (separators normalized to `\n`), spec §7.1. */
internal class ClipboardAction(private val operation: ClipboardOperation) : EditorAction {
    override val id: String = operation.actionId

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: ${operation.name}")
}
