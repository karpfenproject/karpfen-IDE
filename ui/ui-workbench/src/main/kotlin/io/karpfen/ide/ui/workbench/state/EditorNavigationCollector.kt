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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.state.EditorHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

/**
 * Collects the `navigationRequests` of each editor from the moment its tab opens until it closes. Every target goes to
 * [navigate], which is the mediator's `navigateTo` intent. A definition lookup that finishes after a tab switch
 * therefore still navigates. Navigation never depends on a composable being on screen.
 */
internal class EditorNavigationCollector(
    private val scope: CoroutineScope,
    private val navigate: (Location) -> Unit,
) {
    private val collectors = IdentityHashMap<EditorHandle, Job>()

    /** Starts collecting the navigation requests of [editor]. Does nothing if they are collected already. */
    fun start(editor: EditorHandle) {
        if (editor in collectors) return
        collectors[editor] = scope.launch(start = CoroutineStart.UNDISPATCHED) { editor.navigationRequests.collect { navigate(it) } }
    }

    /** Stops collecting the navigation requests of [editor]. */
    fun stop(editor: EditorHandle) {
        collectors.remove(editor)?.cancel()
    }
}
