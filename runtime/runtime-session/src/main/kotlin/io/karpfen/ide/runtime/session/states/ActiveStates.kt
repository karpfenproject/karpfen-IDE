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
package io.karpfen.ide.runtime.session.states

import java.nio.file.Path

/** Where the stack a machine shows comes from. */
public enum class StackOrigin {
    /** The IDE's prediction of the initial stack. The runtime has not reported the machine yet. */
    PREDICTED,

    /** The runtime reported the stack, in a push or in a snapshot. */
    REPORTED,
}

/**
 * The active state configuration of one state machine instance. An object has at most one instance, so the key of the
 * object identifies it.
 *
 * @property attachedTo the key of the object the instance runs on. It is the `modelElementId` of its pushes.
 * @property file the file of the state machine the instance runs: the attached file, or the file of the definition.
 * @property stack the flat stack as the runtime writes it. It holds the plain state names of every parallel branch,
 *   outermost first. The branches are concatenated in split-target order.
 * @property regions the stack split into its branches. Each branch starts at a top-level state.
 * @property previous the stack the instance showed before this one, or null if it showed none. It is kept for
 *   animation.
 * @property origin whether the stack is predicted or reported.
 * @property definition the name of the attachable state machine the instance runs, or null for a state machine that
 *   the deployment attached to the object.
 */
public data class MachineActivity(
    public val attachedTo: String,
    public val file: Path,
    public val stack: List<String>,
    public val regions: List<List<String>>,
    public val previous: List<String>?,
    public val origin: StackOrigin,
    public val definition: String? = null,
)

/**
 * The active state configurations of an environment. Immutable.
 *
 * @property machines every state machine instance the environment has by the key of its object. Before the start
 *   these are the attached machines of the deployment in its order. After the start they are the instances the
 *   runtime announced, in the order of their announcement: an instance appears when it is attached and is gone when
 *   it is detached.
 * @property unknownElements stacks the runtime reported for keys the IDE cannot map to a state machine file, by key, in
 *   the order they were first reported. That is a key without an announced instance, or an instance of a definition
 *   the deployment did not upload.
 */
public data class ActiveStates(
    public val machines: Map<String, MachineActivity>,
    public val unknownElements: Map<String, List<String>>,
)

/**
 * Applies the runtime's reports of stacks and instances to the [ActiveStates] of a deployment. The deployment attaches
 * the machines of [layouts] to objects by key and hands the attachable state machines of [definitions] to the
 * environment, whose instances the runtime announces with the name of their definition. Pure and stateless apart from
 * the layouts, so it can be shared.
 *
 * Before the first report, each attached machine shows its predicted initial stack with [StackOrigin.PREDICTED]. An
 * announced instance shows the predicted initial stack of its state machine until it reports. A report replaces the
 * stack with [StackOrigin.REPORTED] and keeps the stack shown before as [MachineActivity.previous], for animation. A
 * report or snapshot that shows the same stack again keeps the previous stack as it was. A detached instance is
 * removed with its stack. An instance announced again under a key that has one, as at a restart of the environment,
 * replaces it.
 *
 * @param layouts the layouts of the attached machines. A key attached twice keeps its first layout.
 * @param definitions the layouts of the definitions. A name given twice keeps its first layout.
 */
public class ActiveStatesReducer(layouts: List<MachineLayout>, definitions: List<DefinitionLayout> = emptyList()) {
    /** The layouts by attachment key, in deployment order. */
    private val layouts: Map<String, Shape> = LinkedHashMap<String, Shape>().also { map ->
        layouts.forEach { map.putIfAbsent(it.attachedTo, Shape(null, it.file, it.topLevelStates, it.initialStack)) }
    }

    /** The layouts of the definitions by name. */
    private val definitions: Map<String, Shape> = LinkedHashMap<String, Shape>().also { map ->
        definitions.forEach { map.putIfAbsent(it.name, Shape(it.name, it.file, it.topLevelStates, it.initialStack)) }
    }

    /** Every attached machine with its predicted initial stack. */
    public fun initial(): ActiveStates = ActiveStates(layouts.mapValues { (key, shape) -> predicted(key, shape) }, emptyMap())

    /**
     * [states] after the runtime reported [stack] for [modelElementId]. A key without an announced instance takes the
     * attached machine of the key, if the deployment attached one, because its announcement may lie before the
     * observation began. Any other key goes to [ActiveStates.unknownElements].
     */
    public fun reported(states: ActiveStates, modelElementId: String, stack: List<String>): ActiveStates {
        val before = states.machines[modelElementId]
        val shape = before?.let { shapeOf(it.attachedTo, it.definition) } ?: layouts[modelElementId]
            ?: return states.copy(unknownElements = states.unknownElements + (modelElementId to stack.toList()))
        val activity = activity(modelElementId, shape, stack.toList(), before, StackOrigin.REPORTED)
        return states.copy(machines = states.machines + (modelElementId to activity))
    }

    /**
     * [states] after the runtime announced an instance of [definition] on [modelElementId], or of the machine the
     * deployment attached to the key when [definition] is null. The instance shows the predicted initial stack of its
     * state machine. An instance whose state machine the deployment did not upload goes to
     * [ActiveStates.unknownElements] without a stack.
     */
    public fun attached(states: ActiveStates, modelElementId: String, definition: String?): ActiveStates {
        val shape = shapeOf(modelElementId, definition)
            ?: return ActiveStates(states.machines - modelElementId, states.unknownElements + (modelElementId to emptyList()))
        return ActiveStates(states.machines + (modelElementId to predicted(modelElementId, shape)), states.unknownElements - modelElementId)
    }

    /** [states] after the instance on [modelElementId] left. Its stack is gone with it. */
    public fun detached(states: ActiveStates, modelElementId: String): ActiveStates =
        ActiveStates(states.machines - modelElementId, states.unknownElements - modelElementId)

    /**
     * [states] after a snapshot's `activeStates` [reported] and `stateMachines` [instances].
     *
     * Before the environment [started], the snapshot lists no instances. The attached machines then stay: listed ones
     * become reported, the others return to their prediction. Once started, the machines become the listed instances
     * in their order, each reported if the snapshot has its stack and predicted otherwise, since it had no step yet.
     * A reported key that is not listed but has an attached machine follows them as that machine. A snapshot of a
     * started environment that lists no instance at all is read like one before the start, since a runtime that keeps
     * the record of instances lists at least the attached machines of a started environment whose objects exist. The
     * unknown elements become the reported keys that are not such a machine and the listed instances of definitions the
     * deployment did not upload.
     */
    public fun replaced(
        states: ActiveStates,
        reported: Map<String, List<String>>,
        instances: Map<String, String?> = emptyMap(),
        started: Boolean = false,
    ): ActiveStates {
        val listed: Map<String, Shape?> = if (started && instances.isNotEmpty()) {
            instances.mapValues { (key, name) -> shapeOf(key, name) } + reported.keys.filter { it !in instances && it in layouts }.associateWith { layouts[it] }
        } else {
            layouts
        }
        val machines = LinkedHashMap<String, MachineActivity>()
        listed.forEach { (key, shape) ->
            if (shape == null) return@forEach
            val before = states.machines[key]?.takeIf { it.definition == shape.definition }
            machines[key] = reported[key]?.let { activity(key, shape, it.toList(), before, StackOrigin.REPORTED) }
                ?: activity(key, shape, shape.initialStack, before, StackOrigin.PREDICTED)
        }
        val unknown = LinkedHashMap<String, List<String>>()
        reported.filterKeys { it !in machines }.forEach { (key, stack) -> unknown[key] = stack.toList() }
        listed.filter { it.value == null }.keys.forEach { unknown.putIfAbsent(it, emptyList()) }
        return ActiveStates(machines, unknown)
    }

    /** The shape of the instance on [key] that runs [definition], or the attached machine of [key] when it is null. */
    private fun shapeOf(key: String, definition: String?): Shape? = if (definition != null) definitions[definition] else layouts[key]

    /** The predicted activity of an instance on [key] of [shape] before anything was shown. */
    private fun predicted(key: String, shape: Shape): MachineActivity =
        MachineActivity(key, shape.file, shape.initialStack, shape.regions(shape.initialStack), null, StackOrigin.PREDICTED, shape.definition)

    /**
     * The activity of the instance on [key] of [shape] showing [stack] with [origin] after [before]. The stack shown
     * before becomes [MachineActivity.previous]. When the stack stays the same, the previous stack of [before] is kept
     * instead.
     */
    private fun activity(key: String, shape: Shape, stack: List<String>, before: MachineActivity?, origin: StackOrigin): MachineActivity {
        val previous = if (before?.stack == stack) before.previous else before?.stack
        return MachineActivity(key, shape.file, stack, shape.regions(stack), previous, origin, shape.definition)
    }

    /**
     * What every instance of one state machine shares: its definition name, or null for an attached machine, its file,
     * its top-level states and its initial stack.
     */
    private class Shape(val definition: String?, val file: Path, topLevelStates: List<String>, val initialStack: List<String>) {
        /** The segmenter of the stacks of the state machine. */
        private val segmenter = StackSegmenter(topLevelStates)

        /** [stack] split into its branches. */
        fun regions(stack: List<String>): List<List<String>> = segmenter.regions(stack)
    }
}
