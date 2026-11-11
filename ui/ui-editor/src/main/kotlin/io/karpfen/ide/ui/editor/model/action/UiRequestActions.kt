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
 * Requests that the editor model cannot carry out alone and hands to the UI. These are the find bar, find next and
 * find previous, go to line, completion and go to definition.
 *
 * @property actionId the id of the action that sends this request.
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

/** Forwards its [UiRequest] to `EditorActionContext.ui`. It always reports that it did something. */
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
