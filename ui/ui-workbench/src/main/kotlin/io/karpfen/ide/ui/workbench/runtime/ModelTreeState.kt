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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.session.observation.ObservedModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The model tree of a run's Model tab: the rows of the observed model, the objects the user opened and the selected
 * one. The open objects and the selection are kept by key, so they survive every update of the model. Collected in
 * [scope] on the UI thread.
 *
 * @param model the observed model of the run's session.
 */
public class ModelTreeState internal constructor(scope: CoroutineScope, private val model: StateFlow<ObservedModel>) {
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    private val selected = MutableStateFlow<String?>(null)

    /** The rows of the tree. */
    public val rows: StateFlow<List<ModelTreeRow>> = combine(model, expanded, selected) { observed, open, chosen ->
        ModelTreeProjection.rows(observed.mirror, open, chosen)
    }.stateIn(scope, SharingStarted.Eagerly, ModelTreeProjection.rows(model.value.mirror, emptySet(), null))

    /** Opens the object row [key] if it is closed, and closes it otherwise. */
    public fun toggle(key: String) {
        expanded.update { if (key in it) it - key else it + key }
    }

    /** Selects the object [id] and opens the objects that hold it, so its row shows. */
    public fun select(id: String) {
        expanded.update { it + ModelTreeProjection.ownersOf(model.value.mirror, id) }
        selected.value = ModelTreeProjection.keyOf(id)
    }
}
