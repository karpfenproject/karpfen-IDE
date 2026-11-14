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
package io.karpfen.ide.app.headless

import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.runtime.StatechartLog
import io.karpfen.ide.ui.workbench.runtime.StatechartLogReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Writes the log of the runtime of a headless run as it arrives. Every line that the Log pane's Simplified view shows,
 * which is every step of the engine trace and every error, is written in that view's short form, as
 * [RunLines.statechart] puts it, read in order by one [StatechartLogReader], so an exchange reads as one.
 * With [verbose] every line of the log is also written as it is, as [RunLines.raw]
 * puts it, before its short form if it has one. The log is the one the Runtime tool window shows, so the lines the
 * engine writes on every tick are already left out.
 *
 * @param console where the lines go.
 * @param verbose whether every line of the log is written.
 */
internal class RuntimeLogFollower(private val console: HeadlessConsole, private val verbose: Boolean) {

    /**
     * Follows [log] in [scope], from the lines it holds now on, until the returned job is cancelled. A line is written
     * once, also when the log is read again before it changed.
     */
    fun follow(scope: CoroutineScope, log: OutputBuffer): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var written = 0L
        val reader = StatechartLogReader()
        log.revision.collect {
            val lines = log.snapshot().lines.filter { it.sequence > written }
            lines.forEach { line ->
                if (verbose) console.line(RunLines.raw(line))
                if (StatechartLog.shows(line)) console.line(RunLines.statechart(reader.read(line)))
            }
            lines.lastOrNull()?.let { written = it.sequence }
        }
    }
}
