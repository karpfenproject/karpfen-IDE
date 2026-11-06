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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The runs of a workspace, newest first, at most [limit] of them, and the selected one. Adding a run beyond the limit
 * drops the oldest finished runs first, then the oldest ones. Used from the UI thread.
 *
 * @param limit how many runs the list keeps.
 */
internal class RunList(private val limit: Int = MAX_RUNS) {

    private val entries = MutableStateFlow<List<RunEntry>>(emptyList())
    private val selectedId = MutableStateFlow<Long?>(null)
    private val selectedEntry = MutableStateFlow<RunEntry?>(null)

    /** The runs, newest first. */
    val runs: StateFlow<List<RunEntry>> get() = entries.asStateFlow()

    /** The selected run, or null. */
    val selected: StateFlow<RunEntry?> get() = selectedEntry.asStateFlow()

    /** The run [id], or null if the list no longer holds it. */
    fun entry(id: Long): RunEntry? = entries.value.firstOrNull { it.id == id }

    /** Adds [entry] as the newest run and answers the runs it dropped to stay within the limit. */
    fun add(entry: RunEntry): List<RunEntry> {
        val all = listOf(entry) + entries.value
        val dropped = ArrayList<RunEntry>()
        val oldestFirst = all.asReversed()
        oldestFirst.filter { it.phase.isFinished }.take((all.size - limit).coerceAtLeast(0)).forEach(dropped::add)
        oldestFirst.filter { it !in dropped }.take((all.size - dropped.size - limit).coerceAtLeast(0)).forEach(dropped::add)
        publish(all - dropped.toSet())
        return dropped
    }

    /** Replaces the run [id] by [change] of it; nothing if the list no longer holds it. */
    fun update(id: Long, change: (RunEntry) -> RunEntry) {
        if (entries.value.none { it.id == id }) return
        publish(entries.value.map { if (it.id == id) change(it) else it })
    }

    /** Selects the run [id], or nothing for null. */
    fun select(id: Long?) {
        selectedId.value = id
        publish(entries.value)
    }

    /** Removes the finished runs and answers them. */
    fun clearFinished(): List<RunEntry> {
        val finished = entries.value.filter { it.phase.isFinished }
        publish(entries.value - finished.toSet())
        return finished
    }

    /** Publishes [next] as the runs, and the selected run among them. */
    private fun publish(next: List<RunEntry>) {
        entries.value = next
        selectedEntry.value = next.firstOrNull { it.id == selectedId.value }
    }

    companion object {
        /** How many runs a workspace keeps. */
        const val MAX_RUNS: Int = 10
    }
}
