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

import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.layout.LayoutUiState
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Persists the live [LayoutState] into the settings, so the sub-state itself stays a plain holder without I/O. Writes
 * are debounced by [DEBOUNCE] after the last change, so dragging a divider writes only once. A write only happens when
 * the layout differs from what the settings hold. [persistNow] writes once more on exit. Restoring the layout happens
 * in the mediator's `start`, before [start] is called.
 */
internal class LayoutPersister(
    private val scope: CoroutineScope,
    private val layout: LayoutState,
    private val settings: SettingsRepository,
) {
    private var job: Job? = null

    /** Starts writing layout changes, debounced. */
    @OptIn(FlowPreview::class)
    fun start() {
        job?.cancel()
        job = scope.launch {
            layout.state.debounce(DEBOUNCE).collect { current ->
                if (current != LayoutUiState.of(settings.settings.value)) settings.update(current::applyTo)
            }
        }
    }

    /** Writes the current layout now. */
    suspend fun persistNow() {
        val current = layout.state.value
        settings.update(current::applyTo)
    }

    companion object {
        /** The quiet time after the last layout change before the layout is written. */
        val DEBOUNCE: Duration = 500.milliseconds
    }
}
