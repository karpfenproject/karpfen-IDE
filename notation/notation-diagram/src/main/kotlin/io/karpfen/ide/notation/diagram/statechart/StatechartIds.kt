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
package io.karpfen.ide.notation.diagram.statechart

/**
 * The ids by which a statechart and the layout file next to it name the edges of a state machine and their lines.
 * Every rule for these ids lives here, both how an id is made and how a renamed state changes it.
 *
 * - A transition is named by its kind, its states and its order among the transitions with the same kind and
 *   states, as in `NORMAL:off->on#1` or `SPLIT:idle->left,right#1`. A normal transition is drawn as one line, which
 *   has the id of the transition.
 * - A split or join is drawn as one line between each of its states and its bar. Such a line is named by the id of
 *   the transition, a slash, `from:` for a line from a source state into the bar or `to:` for a line out of the bar to
 *   a target state, and the name of that state, as in `SPLIT:idle->left,right#1/to:left`. The bar and the caption of
 *   the split or join keep the id of the transition itself.
 * - The timeout of a state is named by the word `TIMEOUT` and the name of the state, as in `TIMEOUT:cars wait`. A
 *   state has at most one timeout. No transition id starts with that word, so the two never collide.
 */
internal object StatechartIds {

    /** The word that starts the id of a timeout. */
    private const val TIMEOUT = "TIMEOUT"

    /** The id of a transition, with its kind, its sources, its targets and its order. */
    private val TRANSITION = Regex("""^([A-Z]+):(.*)->(.*)#(\d+)$""")

    /** The id of a line between a state and the bar of a split or join. */
    private val BAR_LINE = Regex("""^(.*#\d+)/(from|to):(.*)$""")

    /** The id of a timeout. */
    private val TIMEOUT_ID = Regex("""^$TIMEOUT:(.*)$""")

    /** The part of the id of a transition that names its [kind], its [sources] and its [targets]. */
    fun signature(kind: TransitionKind, sources: List<String>, targets: List<String>): String =
        "$kind:${sources.joinToString(",")}->${targets.joinToString(",")}"

    /** The id of the transition with the [signature] that is the [ordinal]th one with it, counted from 1. */
    fun transition(signature: String, ordinal: Int): String = "$signature#$ordinal"

    /** The id of the line from the state [source] into the bar of the split or join [transition]. */
    fun fromState(transition: String, source: String): String = "$transition/from:$source"

    /** The id of the line from the bar of the split or join [transition] to the state [target]. */
    fun toState(transition: String, target: String): String = "$transition/to:$target"

    /** The id of the timeout of the state [state]. */
    fun timeout(state: String): String = "$TIMEOUT:$state"

    /**
     * [id], an id of a transition, of a line of a split or join or of a timeout, with every mention of the state [old]
     * replaced by [new]. An id of another form is given back as it is.
     */
    fun renamed(id: String, old: String, new: String): String {
        fun name(name: String): String = if (name == old) new else name
        BAR_LINE.matchEntire(id)?.let { match ->
            return "${renamed(match.groupValues[1], old, new)}/${match.groupValues[2]}:${name(match.groupValues[3])}"
        }
        TIMEOUT_ID.matchEntire(id)?.let { return timeout(name(it.groupValues[1])) }
        val match = TRANSITION.matchEntire(id) ?: return id
        fun names(list: String): String = list.split(",").joinToString(",", transform = ::name)
        return "${match.groupValues[1]}:${names(match.groupValues[2])}->${names(match.groupValues[3])}#${match.groupValues[4]}"
    }
}
