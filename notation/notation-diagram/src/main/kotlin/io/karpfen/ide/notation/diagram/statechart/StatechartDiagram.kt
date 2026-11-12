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

import io.karpfen.ide.core.text.TextRange

/**
 * A state machine as a diagram shows it: the states with their nesting and the transitions between them. It is
 * derived from the text of a `.kstates` file by [StatechartProjection] and holds no positions. Where the elements are
 * drawn is the business of the layout.
 *
 * @property attachedTo the type the state machine is attached to, or null if the text names none.
 * @property states the top-level states in the order of the text.
 * @property transitions all transitions in the order of the text. The order matters, because the engine takes the
 *   first transition that can fire.
 */
public data class StatechartDiagram(
    public val attachedTo: String?,
    public val states: List<StateNode>,
    public val transitions: List<TransitionEdge>,
) {
    /** Every state, the nested ones included, each parent before its children. */
    public fun allStates(): List<StateNode> = states.flatMap { it.withDescendants() }

    /** The state named [name], or null. If the text declares the name twice, the first one counts. */
    public fun state(name: String): StateNode? = allStates().firstOrNull { it.name == name }
}

/**
 * A state of a [StatechartDiagram].
 *
 * @property name the name of the state, which identifies it in the state machine.
 * @property isInitial true for an `INITIAL STATE`.
 * @property hasEntry true if the state has an `ENTRY` block.
 * @property hasDo true if the state has a `DO` block.
 * @property children the states nested in this one, in the order of the text.
 * @property range the whole `STATE "name" { ... }` in the text.
 * @property nameRange the name inside its quotes.
 */
public data class StateNode(
    public val name: String,
    public val isInitial: Boolean,
    public val hasEntry: Boolean,
    public val hasDo: Boolean,
    public val children: List<StateNode>,
    public val range: TextRange,
    public val nameRange: TextRange,
) {
    /** True if the state has states nested in it. */
    public val isComposite: Boolean get() = children.isNotEmpty()

    /** This state and every state nested in it, each parent before its children. */
    public fun withDescendants(): List<StateNode> = listOf(this) + children.flatMap { it.withDescendants() }
}

/** The three kinds of transition of the language. */
public enum class TransitionKind {
    /** `TRANSITION "a" -> "b"`, from one state to one state. */
    NORMAL,

    /** `SPLIT "a" -> "b", "c"`, from one state into several that are active side by side. */
    SPLIT,

    /** `JOIN "a", "b" -> "c"`, from several states that are active side by side into one. */
    JOIN,
}

/**
 * A transition of a [StatechartDiagram].
 *
 * @property id identifies the transition within its state machine. It is made of the kind, the states and, where two
 *   transitions share all of these, their order, as in `NORMAL:off->on#1`. A layout file refers to a transition by it.
 * @property kind the kind of transition.
 * @property sources the names of the states the transition leaves. Only a JOIN has more than one.
 * @property targets the names of the states the transition enters. Only a SPLIT has more than one.
 * @property priority the position of the transition among all transitions of the text, starting at 1. A lower number
 *   wins when several transitions can fire.
 * @property label the condition in a few words, or empty for a transition without a condition.
 * @property notLooping true for a transition marked `NOT LOOPING`.
 * @property range the whole transition in the text.
 */
public data class TransitionEdge(
    public val id: String,
    public val kind: TransitionKind,
    public val sources: List<String>,
    public val targets: List<String>,
    public val priority: Int,
    public val label: String,
    public val notLooping: Boolean,
    public val range: TextRange,
)
