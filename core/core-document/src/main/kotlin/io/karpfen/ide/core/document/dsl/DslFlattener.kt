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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.composition.FlatteningProblem
import io.karpfen.dsl.composition.FlatteningResult
import io.karpfen.dsl.composition.StateMachineFlattener
import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import org.slf4j.LoggerFactory

/**
 * Adapter over the flattener of dsl-tools, `StateMachineFlattener` in the package `io.karpfen.dsl.composition`. It
 * turns a state machine whose container states use fragments into one flat state machine, exactly as the runtime does
 * when it activates an environment and as the project verification does when it checks a composition. The IDE uses it
 * where it has to know what the runtime runs: the checks before a deployment and the states a run reports. The adapter
 * is the only way IDE code reaches the flattener, because the architecture tests allow dsl-tools beyond its semantic
 * model only in this package.
 *
 * In the flat state machine each container state holds copies of the states of its fragment, named
 * `<container>/<state>`, with [SEPARATOR] between the parts. Names nest when a fragment has container states itself.
 * The adapter keeps where each flat state was written as a [FlatOrigin], and turns each problem that prevents the
 * flattening into a [FlatteningFinding] with the message of dsl-tools.
 *
 * The adapter never throws for any input, except for fatal VM errors. Should the flattener fail nevertheless, the
 * failure is logged and becomes one finding, so the IDE keeps running. It is thread-safe, because the flattener keeps
 * no state between calls.
 */
public interface DslFlattener {

    /**
     * Flattens [machine] with the fragments among [available], which may hold every state machine of a context and
     * [machine] itself. Fragments are found by their name only, the first state machine of a name wins.
     */
    public fun flatten(machine: StateMachine, available: Collection<StateMachine>): Flattening

    public companion object {
        /** The separator between the name of a container state and the name of a state of its fragment. */
        public const val SEPARATOR: String = StateMachineFlattener.NAME_SEPARATOR
    }
}

/** The default [DslFlattener] over the flattener of dsl-tools. */
public fun DslFlattener(): DslFlattener = DefaultDslFlattener()

/** What a [DslFlattener] made of a state machine and its fragments. */
public sealed interface Flattening {

    /**
     * The composition flattened.
     *
     * @property machine the flat state machine. It has the header of the state machine it started from, and no
     *   container state is left to resolve. Nothing in it is shared with the input.
     * @property origins where each state of [machine] was written, by its flat name, in the order of the states, each
     *   before its inner states.
     */
    public class Flat(public val machine: StateMachine, public val origins: Map<String, FlatOrigin>) : Flattening

    /**
     * The composition does not flatten.
     *
     * @property findings every problem found, each once, in the order dsl-tools found them. Never empty.
     */
    public class NotFlat(public val findings: List<FlatteningFinding>) : Flattening {
        init {
            require(findings.isNotEmpty()) { "a composition that does not flatten has a finding" }
        }
    }
}

/**
 * Where a state of a flat state machine was written. Immutable.
 *
 * @property fragment the name of the fragment the state was written in, or null for a state of the state machine the
 *   composition starts from.
 * @property localName the name of the state in the text it was written in.
 * @property containers the flat names of the container states above the state, the outermost first. It is empty for a
 *   state of the starting state machine. The last one is the container whose fragment holds the state.
 */
public data class FlatOrigin(public val fragment: String?, public val localName: String, public val containers: List<String>) {
    /** The flat name of the container whose fragment holds the state, or null for a state of the starting machine. */
    public val container: String?
        get() = containers.lastOrNull()
}

/**
 * One problem that keeps a composition from flattening. Immutable.
 *
 * @property message the problem in the words of dsl-tools. The runtime refuses an activation with the same words.
 * @property machine the state machine whose text the problem stands in, one of the inputs of the flattening.
 * @property state the name of the state in that text that the problem concerns, such as the container state that uses
 *   an unknown fragment, or null if it concerns a macro or a macro call.
 * @property unknownFragment the name a container state uses that no state machine of the input has, or null for every
 *   other problem.
 */
public class FlatteningFinding(
    public val message: String,
    public val machine: StateMachine,
    public val state: String?,
    public val unknownFragment: String? = null,
) {
    override fun toString(): String = "FlatteningFinding($message)"
}

/** The [DslFlattener] over `StateMachineFlattener` of dsl-tools. The class holds no state. */
internal class DefaultDslFlattener : DslFlattener {

    override fun flatten(machine: StateMachine, available: Collection<StateMachine>): Flattening =
        try {
            when (val result = StateMachineFlattener.flatten(machine, available)) {
                is FlatteningResult.Flattened -> Flattening.Flat(
                    result.machine,
                    result.origins.mapValues { (_, origin) -> FlatOrigin(origin.fragment, origin.localName, origin.containers) },
                )
                is FlatteningResult.NotFlattened -> Flattening.NotFlat(result.problems.map(::findingOf))
            }
        } catch (failure: Exception) {
            logger.error("The flattener of dsl-tools failed on a state machine", failure)
            Flattening.NotFlat(listOf(FlatteningFinding("The state machine could not be flattened: $failure", machine, null)))
        }

    /** The finding of [problem]. */
    private fun findingOf(problem: FlatteningProblem): FlatteningFinding =
        FlatteningFinding(
            message = problem.message,
            machine = problem.machine,
            state = (problem.element as? State)?.name,
            unknownFragment = (problem as? FlatteningProblem.UnknownFragment)?.fragmentName,
        )

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultDslFlattener::class.java)
    }
}
