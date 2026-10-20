package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.caret.CaretModel

/**
 * An editing or navigation operation (Command pattern, spec §1a, §7.1). Key-bound and menu actions are registered by
 * [id] in an [EditorActionRegistry]; parameterized commands (typing text, accepting a completion) are created per
 * use, e.g. with [typeTextAction].
 *
 * Every action that changes text is exactly one undo step and records the carets before and after (Memento).
 */
public interface EditorAction {

    /** Stable id; menus, context menus and keymaps reference it (see [EditorActionIds]). */
    public val id: String

    /** Whether the action can run in [context] (e.g. undo needs history); disabled actions are not executed. */
    public fun isEnabled(context: EditorActionContext): Boolean = true

    /** Runs the action; returns false if it did nothing (lets the key event propagate). */
    public fun execute(context: EditorActionContext): Boolean
}

/**
 * What an [EditorAction] may use. Implemented by the editor state; tests implement it with fakes.
 */
public interface EditorActionContext {

    /** The edited text (apply edits here, with `caretsBefore`/`caretsAfter`). */
    public val text: EditableText

    /** The carets. */
    public val caret: CaretModel

    /**
     * The tokens of the current text if available (they may lag one version behind while tokenizing); actions use
     * them for context such as "inside an EVAL body" (toggle comment uses `#` there).
     */
    public val tokens: TokenizeResult

    /** Number of fully visible lines (page size for Page Up/Down). */
    public val pageLines: Int

    /** System clipboard access. */
    public val clipboard: ClipboardPort

    /** Requests that only the UI can fulfil (open find bar, completion, navigation). */
    public val ui: EditorUiPort
}

/** Clipboard access for copy/cut/paste; the view provides the system clipboard, tests a fake. */
public interface ClipboardPort {
    /** The clipboard text, or null if it holds no text. */
    public fun getText(): String?

    /** Puts [text] on the clipboard. */
    public fun setText(text: String)
}

/** UI requests issued by actions; implemented by the editor state. */
public interface EditorUiPort {
    /** Opens the find bar ([replace]: with the replace row). */
    public fun openFind(replace: Boolean)

    /** Opens the go-to-line popup. */
    public fun openGoToLine()

    /** Opens completion at the caret (explicit request). */
    public fun triggerCompletion()

    /** Navigates to the definition of the symbol at the caret. */
    public fun goToDefinition()

    /** Scrolls so that the primary caret is visible. */
    public fun revealCaret()
}

/** The action that types [text] at every caret (replacing selections), with auto-indent and auto-close rules. */
public fun typeTextAction(text: String): EditorAction = TypeTextAction(text)
