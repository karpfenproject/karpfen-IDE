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
package io.karpfen.ide.runtime.protocol.message

import io.karpfen.ide.runtime.protocol.catalogue.WireNames

/** When the engine halts at a breakpoint of a state. */
public enum class StateTiming(public val wireName: String) {
    /** Before the `ENTRY` block of the state, also when the block is empty. */
    ENTRY("entry"),

    /** When a transition that leaves the state is applied, before the transition starts. */
    EXIT("exit"),
    ;

    public companion object {
        /** The timing whose [wireName] is [name], or null for any other text. */
        public fun ofWireName(name: String): StateTiming? = entries.firstOrNull { it.wireName == name }
    }
}

/** When the engine halts at a breakpoint of a transition. */
public enum class TransitionTiming(public val wireName: String) {
    /** Before the transition changes the stack. */
    START("start"),

    /** After the transition changed the stack, in the same tick. */
    END("end"),
    ;

    public companion object {
        /** The timing whose [wireName] is [name], or null for any other text. */
        public fun ofWireName(name: String): TransitionTiming? = entries.firstOrNull { it.wireName == name }
    }
}

/** The kind of a transition the engine halted at, as the runtime names it. */
public enum class HaltedTransitionKind(public val wireName: String) {
    /** A transition from one state to one state. */
    TRANSITION("TRANSITION"),

    /** A split from one state into several. */
    SPLIT("SPLIT"),

    /** A join from several states into one. */
    JOIN("JOIN"),
    ;

    public companion object {
        /** The kind whose [wireName] is [name], or null for any other text. */
        public fun ofWireName(name: String): HaltedTransitionKind? = entries.firstOrNull { it.wireName == name }
    }
}

/** Where the engine waits at a breakpoint. Immutable. */
public sealed interface EngineHalt {
    /** The key of the object the state machine is attached to. */
    public val attachedTo: String

    /**
     * At a breakpoint of a state.
     *
     * @property state the name of the state.
     * @property timing whether the engine halted at the entry or the exit of the state.
     */
    public data class AtState(
        override val attachedTo: String,
        public val state: String,
        public val timing: StateTiming,
    ) : EngineHalt

    /**
     * At a breakpoint of a transition.
     *
     * @property kind the kind of the transition.
     * @property from the names of its source states.
     * @property to the names of its target states.
     * @property index its place among the transitions of its `.kstates` file, counted from 0, or -1 for a transition
     *   the engine made up for a timeout or a history.
     * @property timing whether the engine halted at the start or the end of the transition.
     */
    public data class AtTransition(
        override val attachedTo: String,
        public val kind: HaltedTransitionKind,
        public val from: List<String>,
        public val to: List<String>,
        public val index: Int,
        public val timing: TransitionTiming,
    ) : EngineHalt
}

/**
 * What the engine of an environment is doing, as the feature `Breakpoint` answers its status and as the runtime pushes
 * it in an `engineStatus` message. Immutable.
 *
 * @property paused true once a pause was asked for or a breakpoint halted the engine, until it is resumed.
 * @property waiting true while the engine thread waits right now, between two ticks or at a breakpoint.
 * @property halt where the engine waits at a breakpoint, or null while it waits at none.
 */
public data class EngineStatus(public val paused: Boolean, public val waiting: Boolean, public val halt: EngineHalt?)

/**
 * The messages to the features `TickByTick` and `Breakpoint` that a debugger sends, and how their answers are read.
 * The status it reads is also the payload of the runtime's `engineStatus` push. The object holds no state.
 */
public object EngineMessages {
    /** The message to `TickByTick` that pauses the engine once the tick it is in has ended. */
    public const val PAUSE: String = WireNames.TICK_PAUSE

    /** The message to `TickByTick` that lets the engine run freely again. */
    public const val RESUME: String = WireNames.TICK_RESUME

    /** The message to `Breakpoint` that asks for the status, `{"action": "getStatus"}`. */
    public val STATUS: String = "{${RuntimeJson.quote(WireNames.BREAKPOINT_ACTION)}: ${RuntimeJson.quote(WireNames.BREAKPOINT_GET_STATUS)}}"

    /**
     * The message to `TickByTick` that lets a waiting engine run [count] ticks, `tick:N`. A halted engine runs to the
     * next breakpoint or to the end of the tick it is in, whichever comes first.
     *
     * @throws IllegalArgumentException if [count] is below 1.
     */
    public fun ticks(count: Int): String {
        require(count >= 1) { "an engine runs at least one tick, not $count" }
        return WireNames.TICK_ADVANCE_PREFIX + count
    }

    /** True if [answer] is the answer of `TickByTick` to a `tick:N` it applies. Any other answer says why it did not. */
    public fun advances(answer: String): Boolean = answer.startsWith(WireNames.TICKS_ADVANCING)

    /**
     * Reads [answer] as the status of `Breakpoint`. It is null when the answer is not a JSON object with the booleans
     * `paused` and `waiting`, or when it has a `halt` that is not a halt as the runtime writes it. A halt names the
     * attached object and its timing, and then either a state or the kind, the source and target states and the index
     * of a transition. The function never throws.
     */
    public fun status(answer: String): EngineStatus? = (RuntimeJson.read(answer) as? JsonReading.Read)?.value?.let(::status)

    /**
     * Reads the JSON [value] as the status of `Breakpoint`, by the same rules as the status in an answer. This is how
     * the payload of an `engineStatus` push is read, which is the same object. It is null when [value] is not such a
     * status. The function never throws.
     */
    public fun status(value: WireValue): EngineStatus? {
        val members = value as? WireValue.WireObject ?: return null
        val paused = (members[WireNames.PAUSED] as? WireValue.WireBoolean)?.value ?: return null
        val waiting = (members[WireNames.WAITING] as? WireValue.WireBoolean)?.value ?: return null
        val halt = when (val value = members[WireNames.HALT]) {
            null, WireValue.WireNull -> null
            is WireValue.WireObject -> halt(value) ?: return null
            else -> return null
        }
        return EngineStatus(paused, waiting, halt)
    }

    /** The halt of [members], or null if they are not a halt as the runtime writes it. */
    private fun halt(members: WireValue.WireObject): EngineHalt? {
        val attachedTo = members.stringMember(WireNames.MODEL_ELEMENT_ID) ?: return null
        val timing = members.stringMember(WireNames.TIMING) ?: return null
        val state = members.stringMember(WireNames.HALT_STATE)
        if (state != null) return StateTiming.ofWireName(timing)?.let { EngineHalt.AtState(attachedTo, state, it) }
        val kind = members.stringMember(WireNames.HALT_KIND)?.let(HaltedTransitionKind::ofWireName) ?: return null
        val from = members.stringsMember(WireNames.HALT_FROM) ?: return null
        val to = members.stringsMember(WireNames.HALT_TO) ?: return null
        val index = members.integralMember(WireNames.HALT_INDEX)?.takeIf { it >= -1 && it <= Int.MAX_VALUE } ?: return null
        return TransitionTiming.ofWireName(timing)?.let { EngineHalt.AtTransition(attachedTo, kind, from, to, index.toInt(), it) }
    }
}
