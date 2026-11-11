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
 * The active state configuration of one attached machine.
 *
 * @property attachedTo the key of the object the machine runs attached to.
 * @property file the machine's file.
 * @property stack the flat stack as the runtime writes it. It holds the plain state names of every parallel branch,
 *   outermost first. The branches are concatenated in split-target order.
 * @property regions the stack split into its branches. Each branch starts at a top-level state.
 * @property previous the stack the machine showed before this one, or null if it showed none. It is kept for
 *   animation.
 * @property origin whether the stack is predicted or reported.
 */
public data class MachineActivity(
    public val attachedTo: String,
    public val file: Path,
    public val stack: List<String>,
    public val regions: List<List<String>>,
    public val previous: List<String>?,
    public val origin: StackOrigin,
)

/**
 * The active state configurations of an environment. Immutable.
 *
 * @property machines every attached machine by its attachment key, in the order of the deployment.
 * @property unknownElements stacks the runtime reported for keys the deployment attaches no machine to, by key, in the
 *   order they were first reported.
 */
public data class ActiveStates(
    public val machines: Map<String, MachineActivity>,
    public val unknownElements: Map<String, List<String>>,
)

/**
 * Applies the runtime's stack reports to the [ActiveStates] of the machines [layouts] describes. Pure and stateless
 * apart from the layouts, so it can be shared.
 *
 * Before the first report, each machine shows its predicted initial stack with [StackOrigin.PREDICTED]. A report
 * replaces it with [StackOrigin.REPORTED] and keeps the stack shown before as [MachineActivity.previous], for
 * animation. A report or snapshot that shows the same stack again keeps the previous stack as it was. A report for a
 * key that the deployment attaches no machine to goes to [ActiveStates.unknownElements].
 *
 * @param layouts the layouts of the attached machines. A key attached twice keeps its first layout.
 */
public class ActiveStatesReducer(layouts: List<MachineLayout>) {
    /** The layouts by attachment key, in deployment order. */
    private val layouts: Map<String, MachineLayout> = LinkedHashMap<String, MachineLayout>().also { map ->
        layouts.forEach { map.putIfAbsent(it.attachedTo, it) }
    }

    /** The segmenters by attachment key. */
    private val segmenters: Map<String, StackSegmenter> = this.layouts.mapValues { StackSegmenter(it.value.topLevelStates) }

    /** Every machine with its predicted initial stack. */
    public fun initial(): ActiveStates = ActiveStates(layouts.mapValues { predicted(it.value) }, emptyMap())

    /** [states] after the runtime reported [stack] for [modelElementId]. */
    public fun reported(states: ActiveStates, modelElementId: String, stack: List<String>): ActiveStates {
        val layout = layouts[modelElementId] ?: return states.copy(unknownElements = states.unknownElements + (modelElementId to stack.toList()))
        val activity = activity(layout, stack.toList(), states.machines[modelElementId], StackOrigin.REPORTED)
        return states.copy(machines = states.machines + (modelElementId to activity))
    }

    /**
     * [states] after a snapshot's `activeStates` [reported]. Listed machines become reported. Machines that the
     * snapshot does not list return to their prediction, because they had no tick since the start. The unknown
     * elements become exactly the listed keys that the deployment does not attach.
     */
    public fun replaced(states: ActiveStates, reported: Map<String, List<String>>): ActiveStates {
        val machines = layouts.mapValues { (key, layout) ->
            val before = states.machines[key]
            reported[key]?.let { activity(layout, it.toList(), before, StackOrigin.REPORTED) }
                ?: activity(layout, layout.initialStack, before, StackOrigin.PREDICTED)
        }
        val unknown = reported.filterKeys { it !in layouts }.mapValues { it.value.toList() }
        return ActiveStates(machines, unknown)
    }

    /** The predicted activity of [layout] before anything was shown. */
    private fun predicted(layout: MachineLayout): MachineActivity =
        MachineActivity(layout.attachedTo, layout.file, layout.initialStack, regionsOf(layout, layout.initialStack), null, StackOrigin.PREDICTED)

    /**
     * The activity of [layout] showing [stack] with [origin] after [before]. The stack shown before becomes
     * [MachineActivity.previous]. When the stack stays the same, the previous stack of [before] is kept instead.
     */
    private fun activity(layout: MachineLayout, stack: List<String>, before: MachineActivity?, origin: StackOrigin): MachineActivity {
        val previous = if (before?.stack == stack) before.previous else before?.stack
        return MachineActivity(layout.attachedTo, layout.file, stack, regionsOf(layout, stack), previous, origin)
    }

    /** [stack] of the machine of [layout] split into its branches. */
    private fun regionsOf(layout: MachineLayout, stack: List<String>): List<List<String>> =
        segmenters.getValue(layout.attachedTo).regions(stack)
}
