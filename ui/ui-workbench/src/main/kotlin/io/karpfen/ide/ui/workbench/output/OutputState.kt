package io.karpfen.ide.ui.workbench.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The errors and warnings of the language tools that arrived while the Language Tools Output was hidden: what the
 * status bar's indicator shows until the tool window is shown again.
 */
public data class UnseenOutputProblems(public val errors: Long = 0, public val warnings: Long = 0) {
    /** True if no error and no warning is unseen. */
    public val isEmpty: Boolean get() = errors == 0L && warnings == 0L
}

/**
 * What the Language Tools Output tool window shows.
 *
 * @property lines the buffered output, oldest first.
 * @property follow true while the view keeps the newest line in sight as output arrives.
 * @property unseen the errors and warnings that arrived while the tool window was hidden.
 */
public data class OutputUiState(
    public val lines: List<OutputLine> = emptyList(),
    public val follow: Boolean = true,
    public val unseen: UnseenOutputProblems = UnseenOutputProblems(),
)

/**
 * The state of the Language Tools Output tool window: follows the [buffer] of the language tools' output and whether
 * the tool window is visible, collected in [scope] on the UI thread. Every new revision of the buffer is read as one
 * snapshot, so a burst of output costs one update of [state].
 *
 * Errors and warnings count as seen once the tool window shows them: while it is visible, every snapshot marks the
 * buffer's counts as seen; while it is hidden, the counts beyond those are [OutputUiState.unseen]. The counts come from
 * the buffer, so problems pushed out of the ring buffer or cleared before anyone looked still count. The binding to the
 * tool window's visibility is a mediator operation and internal.
 */
public class OutputState(private val scope: CoroutineScope, private val buffer: OutputBuffer) {

    private val output = MutableStateFlow(OutputUiState())
    private var seenErrors = 0L
    private var seenWarnings = 0L
    private var binding: Job? = null

    /** What the tool window shows. */
    public val state: StateFlow<OutputUiState> get() = output.asStateFlow()

    /** Whether the view keeps the newest line in sight as output arrives. */
    public fun setFollow(follow: Boolean) {
        output.update { it.copy(follow = follow) }
    }

    /** Removes the buffered output; what arrives afterwards is shown as usual. */
    public fun clear() {
        buffer.clear()
    }

    /** Follows the buffer and whether the tool window is visible, as [visible] tells. Mediator only. */
    internal fun bindVisibility(visible: Flow<Boolean>) {
        binding?.cancel()
        binding = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(buffer.revision, visible) { _, isVisible -> isVisible }.collect(::refresh)
        }
    }

    /** Reads the buffer's current snapshot into [state]; while the tool window [isVisible], its problems count as seen. */
    private fun refresh(isVisible: Boolean) {
        val snapshot = buffer.snapshot()
        if (isVisible) {
            seenErrors = snapshot.appendedErrors
            seenWarnings = snapshot.appendedWarnings
        }
        val unseen = UnseenOutputProblems(snapshot.appendedErrors - seenErrors, snapshot.appendedWarnings - seenWarnings)
        output.update { it.copy(lines = snapshot.lines, unseen = unseen) }
    }
}
