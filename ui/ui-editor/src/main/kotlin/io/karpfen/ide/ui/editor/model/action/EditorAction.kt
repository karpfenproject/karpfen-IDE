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

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.caret.CaretModel

/**
 * An editing or navigation operation. This is the Command pattern. Actions that keys and menus run are registered by
 * [id] in an [EditorActionRegistry]. Actions with parameters, such as typing text or accepting a completion, are
 * created for each use, for example with [typeTextAction].
 *
 * Every action that changes text makes exactly one undo step and records the carets before and after the change, which
 * is the Memento pattern. Actions never scroll. The editor state reveals the primary caret after every executed action
 * and after typed text, in `CodeEditorState.execute`.
 */
public interface EditorAction {

    /** The stable id that menus, context menus and keymaps use to find the action. The ids are in [EditorActionIds]. */
    public val id: String

    /**
     * Whether the action can run in [context]. For example, undo needs history and cut needs text. Disabled actions are
     * not executed.
     *
     * The check must be cheap and free of side effects. It may depend only on the carets, the text and the undo
     * history, because the editor recomputes enablement exactly when those change (`EditorPopupModel.enabledActions`).
     * So it never reads [EditorActionContext.clipboard] or [EditorActionContext.tokens]. An action whose precondition
     * depends on such inputs stays enabled and returns false from [execute] when there is nothing to do. Paste with an
     * empty clipboard is an example.
     */
    public fun isEnabled(context: EditorActionContext): Boolean = true

    /** Runs the action. Returns false if it did nothing, which lets the key event propagate further. */
    public fun execute(context: EditorActionContext): Boolean
}

/**
 * Everything an [EditorAction] may use. The editor state implements it, one context per editor. Tests implement it with
 * fakes and the real models, which do not need Compose.
 */
public interface EditorActionContext {

    /** The edited text. Actions apply their edits here, together with `caretsBefore` and `caretsAfter`. */
    public val text: EditableText

    /** The carets. */
    public val caret: CaretModel

    /**
     * The closers this editor inserted automatically. Typing over one moves the caret over it, and Backspace between an
     * auto-inserted pair deletes both characters. There is one tracker per editor.
     */
    public val autoPairs: AutoClosedPairs

    /**
     * The tokens of exactly the current text, so `tokens.version` always equals `text.snapshot.value.version`. The
     * editor state supplies `DocumentSession.tokensFor(text.snapshot.value)`. That call tokenizes synchronously when the
     * tokenize pipeline of the session lags behind, for example on Cmd+/ right after Enter or a paste. So offsets of the
     * current text and token ranges always agree.
     *
     * Reading this property may tokenize. Actions read it in [EditorAction.execute] only, never in
     * [EditorAction.isEnabled]. Whether an offset is inside an EVAL body is answered by `tokens.embeddedRegionAt(offset)`
     * and never derived from the token kinds. Toggle comment uses `#` inside such a body.
     */
    public val tokens: TokenizeResult

    /** The number of fully visible lines. Page Up and Page Down use it as the page size. */
    public val pageLines: Int

    /**
     * The line comment marker of the edited language. Toggle comment inserts it outside EVAL bodies. It is null for a
     * language without comments, and there toggle comment does nothing. Environment descriptors are such a language,
     * because they are JSON. Every context must state the marker, taken from the language support of the edited
     * document, so that no language is ever commented with the marker of another.
     */
    public val lineComment: String?

    /** Access to the clipboard. */
    public val clipboard: ClipboardPort

    /** Requests that only the UI can carry out, such as opening the find bar, completion and navigation. */
    public val ui: EditorUiPort
}

/**
 * Clipboard access for copy, cut and paste. The view provides the system clipboard, and tests use a fake.
 *
 * Text copied or cut without a selection is put on the clipboard marked as a whole line. While the clipboard still
 * holds it, paste inserts it above the caret's line. The mark belongs to the clipboard content. So it also works between
 * editors, and it disappears as soon as anything else is copied.
 */
public interface ClipboardPort {
    /** The clipboard text, or null if it holds no text. */
    public fun getText(): String?

    /**
     * Puts [text] on the clipboard, marked as a whole line if [wholeLine] is true. Returns false if the clipboard could
     * not be written, for example because another application holds the system clipboard. Cut then keeps the text.
     */
    public fun setText(text: String, wholeLine: Boolean = false): Boolean

    /** True while the clipboard holds a text that was put there with `wholeLine`. Anything else put on the clipboard ends it. */
    public fun holdsWholeLine(): Boolean
}

/** The requests that actions send to the UI. The editor state implements it. */
public interface EditorUiPort {
    /** Opens the find bar. If [replace] is true, the bar also shows the replace row. */
    public fun openFind(replace: Boolean)

    /** Opens the go-to-line popup. */
    public fun openGoToLine()

    /**
     * Runs find next with the editor's `FindModel.next()`. That call selects the match through the executor, so the
     * match is revealed. While the find query is empty, it opens the find bar instead.
     */
    public fun findNext()

    /** Runs find previous. It works like [findNext], with `FindModel.previous()`. */
    public fun findPrevious()

    /** Opens completion at the caret as an explicit request. */
    public fun triggerCompletion()

    /** Navigates to the definition of the symbol at the caret. */
    public fun goToDefinition()
}

/**
 * Runs editor actions in the [EditorActionContext] of an editor. The editor state's `execute(action)` implements it.
 * Models that edit text, such as find and replace or completion accept, receive an executor. So every edit is an
 * [EditorAction] with caret mementos and one undo step, following the Command pattern.
 */
public fun interface EditorActionExecutor {
    /** Runs [action] and returns whether it did something. A disabled action returns false. */
    public fun execute(action: EditorAction): Boolean
}

/**
 * Creates the action that types [text] at the primary caret and replaces the selection. It applies the auto-indent and
 * auto-close rules.
 */
public fun typeTextAction(text: String): EditorAction = TypeTextAction(text)
