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
 * machine, in the form `[12:41:30.123] [env-1] [lamp] TRANSITION_FIRED: off -> on | path=0, from=off, to=on, stack=on`.
 *
 * Every line of a state machine names its path, a number that counts per object from 0 for the life of the
 * environment and is never used twice: `path` for the path a line is on, `paths` while the state machine is split,
 * `starts` for the paths a split starts and `ends` for the paths a join, a forced state or an exchange ends.
 *
 * @property time the time of day the engine wrote, with milliseconds.
 * @property environment the key of the environment the engine runs.
 * @property machine the key of the object the state machine instance runs on, or `*` for the engine as a whole.
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
 * transitions, splits and joins, the entry actions, the state machines that objects get, exchange and lose, and errors.
 * [describe] puts such a step into a few words.
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

    /** The kind of the line on which an object gets a state machine instance. */
    internal const val ATTACHED: String = "STATE_MACHINE_ATTACHED"

    /** The kind of the line on which an object loses its state machine instance. */
    internal const val DETACHED: String = "STATE_MACHINE_DETACHED"

    /** The kind of the line on which an `EXCHANGE` ends the instance of an object and starts another on it. */
    internal const val EXCHANGED: String = "STATE_MACHINE_EXCHANGED"

    /** The kind of the line of a split that fired. */
    internal const val SPLIT: String = "SPLIT_FIRED"

    /** The kind of the line of a join that fired. */
    internal const val JOIN: String = "JOIN_FIRED"

    /** The kind of the line of an initial or a forced stack. */
    internal const val INITIAL: String = "INITIAL_STATE"

    /** The detail that names the definition an instance runs. It is empty for an attached state machine. */
    private const val STATE_MACHINE = "stateMachine"

    /** The detail of an exchange line that names the definition that ended, empty for an attached state machine. */
    private const val ENDED = "ended"

    /** The detail of an exchange line that names the definition that started. */
    private const val STARTED = "started"

    /** The detail that names the path a line is on, or the path a join, a forced state or an exchange starts. */
    internal const val PATH: String = "path"

    /** The detail that names the paths a split starts, separated by commas. */
    internal const val STARTS: String = "starts"

    /** The detail that names the paths a join, a forced state or an exchange ends, separated by commas. */
    internal const val ENDS: String = "ends"

    /**
     * The kinds that report an error: an action that failed, a clause of a condition that failed and counts as false,
     * and an error of the engine as a whole.
     */
    internal val ERRORS = setOf("ACTION_ERROR", "CONDITION_ERROR", "ENGINE_ERROR")

    /** A trace line: time, environment and machine in brackets, the kind, the message and the optional details. */
    private val LINE = Regex("""^\[(\d{2}:\d{2}:\d{2}\.\d{3})] \[([^\]]*)] \[([^\]]*)] ([A-Z_]+): (.*)$""")

    /** What separates the message from the details. */
    private const val DETAILS = " | "

    /**
     * What separates two details: a comma and a blank before the next name and its `=`. A value may hold commas, such
     * as the states of a stack or the paths a split starts, but never a comma and a blank before a name.
     */
    private val DETAIL_SEPARATOR = Regex(""", (?=[A-Za-z]+=)""")

    /** Takes [text] apart, or returns null if it is not a line of the engine trace. */
    public fun parse(text: String): EngineTraceLine? {
        val match = LINE.matchEntire(text.trimEnd()) ?: return null
        val (time, environment, machine, kind, rest) = match.destructured
        val message = rest.substringBefore(DETAILS)
        val details = rest.substringAfter(DETAILS, "")
            .split(DETAIL_SEPARATOR)
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return EngineTraceLine(time, environment, machine, kind, message, details)
    }

    /** True if [text] is a trace line that carries no news, as the class describes. */
    public fun isNoise(text: String): Boolean = parse(text)?.kind in NOISE

    /**
     * What [line] means, in a few words. A transition reads `off -> on`, the way a state machine file writes it. The
     * state names of splits and joins are separated by commas, and they name the paths they start and end, such as
     * `split off -> a, b into paths 1, 2` and `join a, b -> on from paths 1, 2`. An object that gets or loses an
     * instance of a definition reads `attaches glowing` or `detaches glowing`, and an `EXCHANGE` reads
     * `exchanges glowing for blinking`; for a state machine the descriptor attached, the definition's name is replaced
     * by `its state machine`. Kinds without a wording of their own keep the engine's message.
     */
    public fun describe(line: EngineTraceLine): String = when (line.kind) {
        INITIAL -> {
            val stack = line.details["stack"]?.split(',')?.joinToString(" > ") ?: line.message.substringAfter(": ")
            if (line.message.startsWith("Forced")) "set to $stack" else "starts in $stack"
        }
        "TRANSITION_FIRED" -> line.message
        SPLIT -> "split ${spaced(line.message)}" + pathsOf(line, STARTS, " into")
        JOIN -> "join ${spaced(line.message)}" + pathsOf(line, ENDS, " from")
        "STATE_ENTRY_EXEC" -> "runs the entry actions of ${line.details["state"] ?: line.message}"
        ATTACHED -> "attaches ${machineOf(line.details[STATE_MACHINE])}"
        DETACHED -> "detaches ${machineOf(line.details[STATE_MACHINE])}"
        EXCHANGED -> "exchanges ${machineOf(line.details[ENDED])} for ${machineOf(line.details[STARTED])}"
        else -> line.message
    }

    /** The numbers of the paths in the detail [name] of [line], or an empty list if it has none. */
    internal fun numbers(line: EngineTraceLine, name: String): List<String> =
        line.details[name]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** ` into paths 1, 2` for [lead] ` into` and the paths in the detail [name] of [line], or nothing without them. */
    private fun pathsOf(line: EngineTraceLine, name: String, lead: String): String =
        numbers(line, name).takeIf { it.isNotEmpty() }?.let { "$lead paths ${it.joinToString(", ")}" } ?: ""

    /**
     * The state machine that a lifecycle line names with [definition]: the name of the definition, or
     * `its state machine` for a state machine the descriptor attached, whose name is empty.
     */
    private fun machineOf(definition: String?): String = definition?.takeIf { it.isNotEmpty() } ?: "its state machine"

    /** [text] with a blank after every comma that has none. */
    private fun spaced(text: String): String = text.replace(Regex(",(?! )"), ", ")
}
