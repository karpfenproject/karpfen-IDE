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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionContext
import io.karpfen.ide.ui.editor.model.action.EditorActionExecutor
import io.karpfen.ide.ui.editor.model.caret.CaretModel

/**
 * The editor's [EditorActionExecutor] (Command pattern): runs an action in the editor's context if it is enabled, then
 * calls [onCaretsOrTextChanged] when the action changed the text or the carets (the caret reveal: actions never scroll
 * themselves) and [afterRun] after every run (enablement refresh). Every edit of the editor — key-bound actions,
 * typing, completion accept, find selections and replacements — passes here. Used from the UI thread.
 *
 * @param context the action context; read on every run (the context is created after the models that need this
 *   executor, so it is provided lazily).
 */
internal class ActionRunner(
    private val text: EditableText,
    private val caret: CaretModel,
    private val context: () -> EditorActionContext,
    private val onCaretsOrTextChanged: () -> Unit,
    private val afterRun: () -> Unit,
) : EditorActionExecutor {

    override fun execute(action: EditorAction): Boolean {
        val versionBefore = text.snapshot.value.version
        val caretsBefore = caret.current.carets
        val actionContext = context()
        val done = action.isEnabled(actionContext) && action.execute(actionContext)
        if (text.snapshot.value.version != versionBefore || caret.current.carets != caretsBefore) onCaretsOrTextChanged()
        afterRun()
        return done
    }
}
