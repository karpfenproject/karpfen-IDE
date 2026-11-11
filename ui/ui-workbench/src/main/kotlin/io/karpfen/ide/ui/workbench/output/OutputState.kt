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
 * The errors and warnings of the language tools that arrived while the Language Tools Output was hidden. The status
 * bar's indicator shows them until the tool window is shown again.
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
 * The state of the Language Tools Output tool window. It follows the [buffer] of the language tools' output and
 * whether the tool window is visible. Both are collected in [scope] on the UI thread. Every new revision of the buffer
 * is read as one snapshot, so a burst of output costs one update of [state].
 *
 * Errors and warnings count as seen once the tool window shows them. While the window is visible, every snapshot marks
 * the buffer's counts as seen. While it is hidden, the counts beyond those are [OutputUiState.unseen]. The counts come
 * from the buffer, so problems still count when they were pushed out of the ring buffer or cleared before anyone
 * looked. Binding the state to the tool window's visibility is an operation of the mediator and is internal.
 *
 * The tool window opens where the user left it, at the [readingPosition] that the view keeps here while the window is
 * hidden. The exception is when problems arrived in the meantime. The window then opens in follow mode, so the newest
 * lines are in sight, and the unseen problems are among them.
 */
public class OutputState(private val scope: CoroutineScope, private val buffer: OutputBuffer) {

    private val output = MutableStateFlow(OutputUiState())
    private var seenErrors = 0L
    private var seenWarnings = 0L
    private var wasVisible = false
    private var binding: Job? = null

    /** What the tool window shows. */
    public val state: StateFlow<OutputUiState> get() = output.asStateFlow()

    /**
     * Where the user left the output, or null before the view showed any line. It is not part of [state], because it
     * changes with every scrolled frame. Only the view reads it, as the place to show when it enters the composition
     * again.
     */
    public var readingPosition: OutputReadingPosition? = null
        private set

    /** Keeps [position] as the place where the user left the output. Called by the view as the user scrolls. */
    public fun keepReadingPosition(position: OutputReadingPosition) {
        readingPosition = position
    }

    /** Whether the view keeps the newest line in sight as output arrives. */
    public fun setFollow(follow: Boolean) {
        output.update { it.copy(follow = follow) }
    }

    /** Removes the buffered output. What arrives afterwards is shown as usual. */
    public fun clear() {
        buffer.clear()
    }

    /** Follows the buffer and whether the tool window is visible, as [visible] tells. Only the mediator calls it. */
    internal fun bindVisibility(visible: Flow<Boolean>) {
        binding?.cancel()
        binding = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(buffer.revision, visible) { _, isVisible -> isVisible }.collect(::refresh)
        }
    }

    /**
     * Reads the buffer's current snapshot into [state]. While the tool window is visible, as [isVisible] says, its
     * problems count as seen. When the window is shown while there are unseen problems, it switches to following the
     * newest lines.
     */
    private fun refresh(isVisible: Boolean) {
        val snapshot = buffer.snapshot()
        val shownWithUnseen = isVisible && !wasVisible && !unseenIn(snapshot).isEmpty
        wasVisible = isVisible
        if (isVisible) {
            seenErrors = snapshot.appendedErrors
            seenWarnings = snapshot.appendedWarnings
        }
        output.update { it.copy(lines = snapshot.lines, follow = it.follow || shownWithUnseen, unseen = unseenIn(snapshot)) }
    }

    /** The problems of [snapshot] beyond those counted as seen. */
    private fun unseenIn(snapshot: OutputSnapshot): UnseenOutputProblems =
        UnseenOutputProblems(snapshot.appendedErrors - seenErrors, snapshot.appendedWarnings - seenWarnings)
}
