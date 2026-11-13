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

/**
 * One line of the runtime's engine trace, taken apart. The engine writes such a line for every step of a state
 * machine, in the form `[12:41:30.123] [env-1] [lamp] TRANSITION_FIRED: off -> on | from=off, to=on`.
 *
 * @property time the time of day the engine wrote, with milliseconds.
 * @property environment the key of the environment the engine runs.
 * @property machine the key of the object the state machine is attached to, or `*` for the engine as a whole.
 * @property kind the kind of step, as the engine names it, for example `TRANSITION_FIRED`.
 * @property message what the engine says about the step.
 * @property details the values the engine adds after the message, by their names.
 */
public data class EngineTraceLine(
    public val time: String,
    public val environment: String,
    public val machine: String,
    public val kind: String,
    public val message: String,
    public val details: Map<String, String> = emptyMap(),
) {
    /** True for a step that went wrong. */
    public val isError: Boolean get() = kind in EngineTrace.ERRORS

    /** The name of the state machine for the reader. The engine as a whole is called "engine". */
    public val subject: String get() = if (machine == EngineTrace.WHOLE_ENGINE) "engine" else machine
}

/**
 * Reads the engine trace out of the runtime's console output. The IDE's runtime container prints the trace, and the
 * run's log shows it. The trace is the only place where the runtime tells which transition fired, so the simplified
 * log is built from it.
 *
 * The engine writes several lines on every tick of every state machine, whether or not anything happens. They say
 * that a tick started and ended and that a state has no actions to run. It also announces every transition, split
 * and join before it applies it, in the words of the line that follows once it is applied. [isNoise] recognizes
 * both, so they never reach the log. What is left are the steps a reader follows: the initial states, the
 * transitions, splits and joins, the entry actions, and errors. [describe] puts such a step into a few words.
 */
public object EngineTrace {

    /** The machine name the engine uses for itself. */
    public const val WHOLE_ENGINE: String = "*"

    /**
     * The kinds that carry no news: those the engine writes on every tick, and the announcements of a transition, a
     * split and a join, which the line of the applied one repeats.
     */
    private val NOISE = setOf(
        "TICK_START", "TICK_END", "STATE_DO_EXEC", "STATE_DO_SKIP", "STATE_ENTRY_SKIP", "TRANSITION_CHECK",
        "TRANSITION_SKIPPED_LOOP", "TRANSITION_START", "SPLIT_START", "JOIN_START",
    )

    /** The kinds that report an error. */
    internal val ERRORS = setOf("ACTION_ERROR", "ENGINE_ERROR")

    /** A trace line: time, environment and machine in brackets, the kind, the message and the optional details. */
    private val LINE = Regex("""^\[(\d{2}:\d{2}:\d{2}\.\d{3})] \[([^\]]*)] \[([^\]]*)] ([A-Z_]+): (.*)$""")

    /** What separates the message from the details. */
    private const val DETAILS = " | "

    /** Takes [text] apart, or returns null if it is not a line of the engine trace. */
    public fun parse(text: String): EngineTraceLine? {
        val match = LINE.matchEntire(text.trimEnd()) ?: return null
        val (time, environment, machine, kind, rest) = match.destructured
        val message = rest.substringBefore(DETAILS)
        val details = rest.substringAfter(DETAILS, "")
            .split(", ")
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return EngineTraceLine(time, environment, machine, kind, message, details)
    }

    /** True if [text] is a trace line that carries no news, as the class describes. */
    public fun isNoise(text: String): Boolean = parse(text)?.kind in NOISE

    /**
     * What [line] means, in a few words. A transition reads `off -> on`, the way a state machine file writes it. The
     * state names of splits and joins are separated by commas. Kinds without a wording of their own keep the engine's
     * message.
     */
    public fun describe(line: EngineTraceLine): String = when (line.kind) {
        "INITIAL_STATE" -> {
            val stack = line.details["stack"]?.split(',')?.joinToString(" > ") ?: line.message.substringAfter(": ")
            if (line.message.startsWith("Forced")) "set to $stack" else "starts in $stack"
        }
        "TRANSITION_FIRED" -> line.message
        "SPLIT_FIRED" -> "split ${spaced(line.message)}"
        "JOIN_FIRED" -> "join ${spaced(line.message)}"
        "STATE_ENTRY_EXEC" -> "runs the entry actions of ${line.details["state"] ?: line.message}"
        else -> line.message
    }

    /** [text] with a blank after every comma that has none. */
    private fun spaced(text: String): String = text.replace(Regex(",(?! )"), ", ")
}
