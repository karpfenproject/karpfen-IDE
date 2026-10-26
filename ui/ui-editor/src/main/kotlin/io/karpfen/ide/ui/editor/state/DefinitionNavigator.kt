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

import io.karpfen.ide.core.lang.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Go to definition ([CodeEditorState.goToDefinition]): looks the target up with [definitionsAt] in [scope] and emits
 * the first location on [navigationRequests] (no replay: its owner collects it for the editor's whole life, so no
 * lookup is lost on a tab switch); no-op if there is none. A newer request cancels a running one.
 */
internal class DefinitionNavigator(
    private val scope: CoroutineScope,
    private val definitionsAt: suspend (offset: Int) -> List<Location>,
) {

    private val requests = MutableSharedFlow<Location>(extraBufferCapacity = BUFFER)
    private var running: Job? = null

    /** Navigation targets, without replay. */
    val navigationRequests: SharedFlow<Location> = requests.asSharedFlow()

    /** Navigates to the definition of the symbol at [offset] of the current text. */
    fun goToDefinition(offset: Int) {
        running?.cancel()
        running = scope.launch { definitionsAt(offset).firstOrNull()?.let { requests.emit(it) } }
    }

    /** The buffer size of the request stream. */
    private companion object {
        /** Targets kept for slow collectors; navigation requests are rare, so a few suffice. */
        const val BUFFER = 8
    }
}
