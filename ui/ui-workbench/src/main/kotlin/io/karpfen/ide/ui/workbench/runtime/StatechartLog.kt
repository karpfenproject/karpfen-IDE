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

import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One line of the simplified log.
 *
 * @property time the time of day with milliseconds.
 * @property subject the state machine the line is about, "engine" for the engine as a whole, or empty for a line that
 *   belongs to no state machine.
 * @property text what happened.
 * @property isError true for an error.
 */
public data class StatechartLogEntry(
    public val time: String,
    public val subject: String,
    public val text: String,
    public val isError: Boolean,
)

/**
 * The simplified view of the runtime log. It keeps what a reader needs to follow the state machines and leaves out
 * the rest of the runtime's output, such as the lines about the server, the sockets and the clients.
 *
 * It shows the steps of the engine trace in the wording of [EngineTrace.describe]: the initial states, the transitions,
 * splits and joins, the entry actions, the instances that objects get and lose, and the engine's start and stop. It
 * also shows every error line, because an error in an action or in Python explains why a state machine did not do
 * what was expected.
 *
 * An `EXCHANGE` shows as two lines of the engine on the same object: the old instance is detached, then the new one
 * attached. The engine writes the lines of the instances that leave and join in one tick together, the detached ones
 * first, so an attached line is an exchange if the detached line of its object comes before it in the same group.
 * [StatechartLogReader] reads lines in order and remembers that group; [entry] reads a single line without it.
 */
public object StatechartLog {

    /** The time of day of a line that carries no time of its own. */
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /** The longest subject that gets a column of its own width. Longer names push their text to the right. */
    private const val MAX_SUBJECT_WIDTH = 24

    /** True if the simplified log shows [line]. That is a step of the engine trace or an error. */
    public fun shows(line: OutputLine): Boolean = line.level == OutputLevel.ERROR || EngineTrace.parse(line.message) != null

    /** [lines], in order, as the simplified log shows them, with exchanges recognized. */
    public fun entries(lines: List<OutputLine>): List<StatechartLogEntry> {
        val reader = StatechartLogReader()
        return lines.map(reader::read)
    }

    /** [line] as the simplified log shows it, read on its own, so an exchange reads as an attachment. */
    public fun entry(line: OutputLine): StatechartLogEntry {
        val step = EngineTrace.parse(line.message)
            ?: return StatechartLogEntry(TIME.format(line.time), "", line.message, line.level == OutputLevel.ERROR)
        return StatechartLogEntry(step.time, step.subject, EngineTrace.describe(step), step.isError || line.level == OutputLevel.ERROR)
    }

    /**
     * The width of the subject column for [lines] in characters. It is the longest subject among them, at most
     * [MAX_SUBJECT_WIDTH], so the texts of all lines start in one column.
     */
    public fun subjectWidth(lines: List<OutputLine>): Int = widthOf(lines.map(::entry))

    /** The width of the subject column for [entries] in characters, by the rule of [subjectWidth]. */
    public fun widthOf(entries: Collection<StatechartLogEntry>): Int =
        entries.maxOfOrNull { it.subject.length }?.coerceAtMost(MAX_SUBJECT_WIDTH) ?: 0
}

/**
 * Reads the lines of the runtime's log in order as the simplified log shows them. It remembers the group of lifecycle
 * lines of each environment that the engine is writing, the lines on which objects get and lose instances, so it reads
 * an attached line as an exchange if the detached line of the same object came before it in the group. Any other line
 * of the engine trace of that environment ends the group. Not thread-safe; a reader belongs to one caller.
 */
public class StatechartLogReader {
    /** The detached lines of the group each environment is writing, by environment and then by object key. */
    private val detached = HashMap<String, LinkedHashMap<String, EngineTraceLine>>()

    /** [line] as the simplified log shows it, after the lines read before. */
    public fun read(line: OutputLine): StatechartLogEntry {
        val step = EngineTrace.parse(line.message) ?: return StatechartLog.entry(line)
        val group = detached.getOrPut(step.environment) { LinkedHashMap() }
        return when (step.kind) {
            EngineTrace.DETACHED -> {
                group[step.machine] = step
                StatechartLog.entry(line)
            }
            EngineTrace.ATTACHED -> {
                val old = group.remove(step.machine) ?: return StatechartLog.entry(line)
                StatechartLogEntry(step.time, step.subject, EngineTrace.exchanged(old, step), line.level == OutputLevel.ERROR)
            }
            else -> {
                group.clear()
                StatechartLog.entry(line)
            }
        }
    }
}
