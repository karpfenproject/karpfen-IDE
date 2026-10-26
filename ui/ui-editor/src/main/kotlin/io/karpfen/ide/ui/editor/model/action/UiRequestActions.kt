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

/**
 * Requests handled by the UI (find bar, find next/previous, go to line, completion, go to definition).
 *
 * @property actionId the id of the action of this request.
 */
internal enum class UiRequest(val actionId: String) {
    FIND(EditorActionIds.FIND),
    REPLACE(EditorActionIds.REPLACE),
    FIND_NEXT(EditorActionIds.FIND_NEXT),
    FIND_PREVIOUS(EditorActionIds.FIND_PREVIOUS),
    GO_TO_LINE(EditorActionIds.GO_TO_LINE),
    COMPLETION(EditorActionIds.COMPLETION),
    GO_TO_DEFINITION(EditorActionIds.GO_TO_DEFINITION),
}

/** Forwards a [UiRequest] to `EditorActionContext.ui`. */
internal class UiRequestAction(private val request: UiRequest) : EditorAction {
    override val id: String = request.actionId

    override fun execute(context: EditorActionContext): Boolean {
        val ui = context.ui
        when (request) {
            UiRequest.FIND -> ui.openFind(replace = false)
            UiRequest.REPLACE -> ui.openFind(replace = true)
            UiRequest.FIND_NEXT -> ui.findNext()
            UiRequest.FIND_PREVIOUS -> ui.findPrevious()
            UiRequest.GO_TO_LINE -> ui.openGoToLine()
            UiRequest.COMPLETION -> ui.triggerCompletion()
            UiRequest.GO_TO_DEFINITION -> ui.goToDefinition()
        }
        return true
    }
}
