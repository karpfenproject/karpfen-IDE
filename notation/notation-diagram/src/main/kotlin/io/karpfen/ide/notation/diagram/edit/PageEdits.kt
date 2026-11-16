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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes of the pages of a state machine that its statechart offers, each as edits of the text: container states
 * that run a fragment, final states that end a fragment, and the clause `DONE` that waits for that end. Every function
 * takes the text and the diagram that was derived from exactly that text, and gives the change or null if there is
 * nothing to change.
 *
 * The edits write what the language allows and nothing else: a final state only at the top level of a fragment, and
 * `DONE` only on a transition or split that leaves a container state or a join of which a source is one. Which
 * fragments exist is the business of the caller, since a diagram shows one file.
 */
public object PageEdits {

    private const val CONTAINER_STATE = "CONTAINER STATE"
    private const val FINAL_STATE = "FINAL STATE"
    private const val USE = "USE"
    private const val DONE = "DONE"
    private const val CONDITION = "CONDITION"

    /**
     * Adds the container state [name], which runs the fragment [fragment], at the top level or nested in the state
     * [parent]. [at] and [parentFrame] place it as [StatechartEdits.addState] places a state.
     */
    public fun addContainer(
        text: String,
        diagram: StatechartDiagram,
        parent: String?,
        name: String,
        fragment: String,
        at: DiagramPoint? = null,
        parentFrame: DiagramBox? = null,
    ): DiagramChange? {
        if (fragment.isBlank()) return null
        if (parent != null && diagram.state(parent)?.let { it.isContainer || it.isFinal } != false) return null
        val statement = "$CONTAINER_STATE ${StatechartEdits.quoted(name)} $USE ${StatechartEdits.quoted(fragment)} { }"
        return StatechartEdits.addStatement(text, diagram, parent, name, statement, at, parentFrame)
    }

    /** Adds the final state [name] at the top level of a fragment. A state machine that is no fragment has none. */
    public fun addFinal(text: String, diagram: StatechartDiagram, name: String, at: DiagramPoint? = null): DiagramChange? {
        if (!diagram.isFragment) return null
        return StatechartEdits.addStatement(text, diagram, null, name, "$FINAL_STATE ${StatechartEdits.quoted(name)} { }", at, null)
    }

    /**
     * Lets the container state [name] run the fragment [fragment]. Only the name after `USE` changes, or `USE` with the
     * name is written behind the name of the state if the text has none yet.
     */
    public fun useFragment(text: String, diagram: StatechartDiagram, name: String, fragment: String): DiagramChange? {
        val state = diagram.state(name)?.takeIf { it.isContainer } ?: return null
        val chosen = fragment.trim().replace('"', '\'')
        if (chosen.isEmpty() || chosen == state.fragment) return null
        val range = state.fragmentRange
        if (range != null) return DiagramChange(listOf(TextEdit(range, chosen)))
        val at = state.nameRange.end + 1
        return DiagramChange(listOf(TextEdit(TextRange(at, at), " $USE ${StatechartEdits.quoted(chosen)}")))
    }

    /**
     * True if the language allows `DONE` on the transition [id]: a transition or a split whose source is a container
     * state, or a join of which at least one source is a container state.
     */
    public fun allowsDone(diagram: StatechartDiagram, id: String): Boolean {
        val transition = diagram.transitions.firstOrNull { it.id == id } ?: return false
        val containers = transition.sources.mapNotNull(diagram::state).filter { it.isContainer }
        return when (transition.kind) {
            TransitionKind.JOIN -> containers.isNotEmpty()
            TransitionKind.NORMAL, TransitionKind.SPLIT -> containers.size == transition.sources.size && containers.isNotEmpty()
        }
    }

    /**
     * Puts the clause `DONE` into the condition of the transition [id], in front of the clauses it has. A transition
     * without a condition gets one that holds `DONE` alone, after its breakpoints, where the language wants it.
     */
    public fun addDone(text: String, diagram: StatechartDiagram, id: String): DiagramChange? {
        val transition = diagram.transitions.firstOrNull { it.id == id }?.takeIf { !it.isDone && allowsDone(diagram, id) } ?: return null
        val condition = transition.conditionRange
        if (condition == null) {
            val body = TextBlocks.blockFrom(text, transition.range.start) ?: return null
            return DiagramChange(listOf(TextBlocks.insert(text, body, "$CONDITION { $DONE }", after = transition.breakpointRange)))
        }
        val braces = TextBlocks.blockFrom(text, condition.start) ?: return null
        if ('\n' in text.substring(braces.start, braces.end)) return DiagramChange(listOf(TextBlocks.insert(text, braces, DONE, after = null)))
        val first = (braces.start + 1 until braces.end - 1).firstOrNull { !text[it].isWhitespace() } ?: return null
        return DiagramChange(listOf(TextEdit(TextRange(first, first), "$DONE ")))
    }

    /**
     * Takes the clause `DONE` out of the condition of the transition [id]. If it is the only clause, the whole
     * condition goes, since a condition without clauses is no condition of the language. Otherwise the word goes with
     * the blanks behind it, or its line if nothing else stands on it.
     */
    public fun removeDone(text: String, diagram: StatechartDiagram, id: String): DiagramChange? {
        val transition = diagram.transitions.firstOrNull { it.id == id } ?: return null
        val done = transition.doneRange ?: return null
        val condition = transition.conditionRange ?: return null
        val braces = TextBlocks.blockFrom(text, condition.start) ?: return null
        val others = text.substring(braces.start + 1, done.start) + text.substring(done.end, braces.end - 1)
        if (others.isBlank()) return DiagramChange(listOf(TextBlocks.removal(text, condition)))
        val removal = TextBlocks.removal(text, done)
        if (removal.range != done) return DiagramChange(listOf(removal))
        val end = (done.end until braces.end - 1).firstOrNull { text[it] != ' ' && text[it] != '\t' } ?: done.end
        return DiagramChange(listOf(TextEdit(TextRange(done.start, end), "")))
    }
}
