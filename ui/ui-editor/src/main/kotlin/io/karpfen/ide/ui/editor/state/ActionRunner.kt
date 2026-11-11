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
 * The [EditorActionExecutor] of the editor, following the Command pattern. It runs an action in the editor's context if
 * the action is enabled. When the action changed the text or the carets, it then calls [onCaretsOrTextChanged], which
 * reveals the caret, because actions never scroll themselves. After every run it calls [afterRun], which refreshes
 * the action enablement.
 *
 * Every edit of the editor passes through here. That includes actions bound to keys, typing, completion accept, and
 * the selections and replacements of find. Use it from the UI thread.
 *
 * @param context supplies the action context. It is read on every run, because the context is created after the
 *   models that need this executor.
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
