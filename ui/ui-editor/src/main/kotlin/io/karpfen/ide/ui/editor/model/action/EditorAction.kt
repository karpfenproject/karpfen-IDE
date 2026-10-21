package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.caret.CaretModel

/**
 * An editing or navigation operation (Command pattern, spec §1a, §7.1). Key-bound and menu actions are registered by
 * [id] in an [EditorActionRegistry]; parameterized commands (typing text, accepting a completion) are created per
 * use, e.g. with [typeTextAction].
 *
 * Every action that changes text is exactly one undo step and records the carets before and after (Memento). Actions
 * never scroll: the editor state reveals the primary caret after every executed action and typed text (see
 * `CodeEditorState.execute`).
 */
public interface EditorAction {

    /** Stable id; menus, context menus and keymaps reference it (see [EditorActionIds]). */
    public val id: String

    /**
     * Whether the action can run in [context] (e.g. undo needs history, cut needs text); disabled actions are not
     * executed. Cheap and side-effect free, and it depends only on the carets, the text and the undo history, because
     * the editor recomputes enablement whenever those change (`EditorPopupModel.enabledActions`): it never reads
     * [EditorActionContext.clipboard] or [EditorActionContext.tokens]. An action whose precondition depends on such
     * inputs stays enabled and returns false from [execute] when there is nothing to do (Paste with an empty clipboard).
     */
    public fun isEnabled(context: EditorActionContext): Boolean = true

    /** Runs the action; returns false if it did nothing (lets the key event propagate). */
    public fun execute(context: EditorActionContext): Boolean
}

/**
 * What an [EditorAction] may use. Implemented by the editor state (one per editor); tests implement it with fakes and
 * the real Compose-free models.
 */
public interface EditorActionContext {

    /** The edited text (apply edits here, with `caretsBefore`/`caretsAfter`). */
    public val text: EditableText

    /** The carets. */
    public val caret: CaretModel

    /**
     * The closers this editor inserted automatically (typing over one moves over it, Backspace between an
     * auto-inserted pair deletes both; spec §7.1). One tracker per editor.
     */
    public val autoPairs: AutoClosedPairs

    /**
     * The tokens of exactly the current text (`tokens.version == text.snapshot.value.version`, MUST): the editor state
     * supplies `DocumentSession.tokensFor(text.snapshot.value)`, which tokenizes synchronously when the session's
     * tokenize pipeline lags (e.g. Cmd+/ right after Enter or a paste), so offsets of the current text and token ranges
     * always agree. Reading it may therefore tokenize; actions read it in [EditorAction.execute] only, never in
     * [EditorAction.isEnabled]. "Inside an EVAL body" is `tokens.embeddedRegionAt(offset)` (toggle comment uses `#`
     * there), never derived from the token kinds.
     */
    public val tokens: TokenizeResult

    /** Number of fully visible lines (page size for Page Up/Down). */
    public val pageLines: Int

    /** System clipboard access. */
    public val clipboard: ClipboardPort

    /** Requests that only the UI can fulfil (open find bar, completion, navigation). */
    public val ui: EditorUiPort
}

/**
 * Clipboard access for copy/cut/paste; the view provides the system clipboard, tests a fake. A text copied or cut
 * without a selection is put there marked as a *whole line*; paste inserts it above the caret's line while the clipboard
 * still holds it (the mark belongs to the clipboard's content, so it also holds between editors and disappears when
 * anything else is copied).
 */
public interface ClipboardPort {
    /** The clipboard text, or null if it holds no text. */
    public fun getText(): String?

    /**
     * Puts [text] on the clipboard, marked as a whole line if [wholeLine]. Returns false if the clipboard could not be
     * written (e.g. another application holds the system clipboard); cut then keeps the text.
     */
    public fun setText(text: String, wholeLine: Boolean = false): Boolean

    /** True while the clipboard holds a text put there with `wholeLine` (until anything else is put on the clipboard). */
    public fun holdsWholeLine(): Boolean
}

/** UI requests issued by actions; implemented by the editor state. */
public interface EditorUiPort {
    /** Opens the find bar ([replace]: with the replace row). */
    public fun openFind(replace: Boolean)

    /** Opens the go-to-line popup. */
    public fun openGoToLine()

    /**
     * Find next (FIND_NEXT): the editor's `FindModel.next()` (which selects through the executor, so the match is
     * revealed); opens the find bar instead while the find query is empty.
     */
    public fun findNext()

    /** Find previous (FIND_PREVIOUS): like [findNext] with `FindModel.previous()`. */
    public fun findPrevious()

    /** Opens completion at the caret (explicit request). */
    public fun triggerCompletion()

    /** Navigates to the definition of the symbol at the caret. */
    public fun goToDefinition()
}

/**
 * Runs editor actions in an editor's [EditorActionContext] (the editor state's `execute(action)`). Models that edit
 * text (find/replace, completion accept) receive one, so every edit is an [EditorAction] with caret mementos and one
 * undo step (spec §1a, Command pattern).
 */
public fun interface EditorActionExecutor {
    /** Runs [action]; returns whether it did something (false if disabled). */
    public fun execute(action: EditorAction): Boolean
}

/** The action that types [text] at every caret (replacing selections), with auto-indent and auto-close rules. */
public fun typeTextAction(text: String): EditorAction = TypeTextAction(text)
