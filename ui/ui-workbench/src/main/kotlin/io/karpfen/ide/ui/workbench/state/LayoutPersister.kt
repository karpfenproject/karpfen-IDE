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
 * Persists the live [LayoutState] into the settings (decision 31 of the work-package plan): debounced ([DEBOUNCE]
 * after the last change, so dragging a divider writes once) and only when it differs from what the settings hold, and
 * once more on exit ([persistNow]). Restoring happens in the mediator's `start` before [start].
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
        /** Quiet time after the last layout change before it is written. */
        val DEBOUNCE: Duration = 500.milliseconds
    }
}
