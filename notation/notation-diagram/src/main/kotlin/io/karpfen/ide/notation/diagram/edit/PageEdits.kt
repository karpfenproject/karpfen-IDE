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
import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TransitionEdge
import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes of the pages of a state machine that its statechart offers, each as edits of the text: container states
 * that run a fragment, a state that becomes a container state and back, final states that end a fragment, and the
 * clause `DONE` that waits for that end. Every function takes the text and the diagram that was derived from exactly
 * that text, and gives the change or null if there is nothing to change.
 *
 * The edits write what the language allows and nothing else: a final state only at the top level of a fragment, a
 * container state without actions and inner states, and `DONE` only on a transition or split that leaves a container
 * state or a join of which a source is one. A container state is written without `USE`, because a user often adds it
 * before the fragment exists, and the name of its fragment is written as the user types it. Neither is checked against
 * the files of the project, since a diagram shows one file. dsl-tools reports a container state without a fragment and
 * a fragment that no file declares, and these errors are wanted until the fragment exists.
 */
public object PageEdits {

    private const val CONTAINER_STATE = "CONTAINER STATE"
    private const val FINAL_STATE = "FINAL STATE"
    private const val USE = "USE"
    private const val DONE = "DONE"
    private const val CONDITION = "CONDITION"
    private const val CONTAINER = "CONTAINER"
    private const val STATE = "STATE"
    private val CONTAINER_WORD = Regex("""\bCONTAINER\s+""")
    private val HISTORY = Regex("""\b(SHALLOW|DEEP)\s+HISTORY\s+""")

    /**
     * Adds the container state [name] without a fragment, `CONTAINER STATE "name" { }`, at the top level or nested in the
     * state [parent]. [at] and [parentFrame] place it as [StatechartEdits.addState] places a state.
     */
    public fun addContainer(
        text: String,
        diagram: StatechartDiagram,
        parent: String?,
        name: String,
        at: DiagramPoint? = null,
        parentFrame: DiagramBox? = null,
    ): DiagramChange? {
        if (parent != null && diagram.state(parent)?.let { it.isContainer || it.isFinal } != false) return null
        val statement = "$CONTAINER_STATE ${StatechartEdits.quoted(name)} { }"
        return StatechartEdits.addStatement(text, diagram, parent, name, statement, at, parentFrame)
    }

    /** Adds the final state [name] at the top level of a fragment. A state machine that is no fragment has none. */
    public fun addFinal(text: String, diagram: StatechartDiagram, name: String, at: DiagramPoint? = null): DiagramChange? {
        if (!diagram.isFragment) return null
        return StatechartEdits.addStatement(text, diagram, null, name, "$FINAL_STATE ${StatechartEdits.quoted(name)} { }", at, null)
    }

    /**
     * The name of a fragment as the text writes it after `USE`, from the name [typed] by the user: trimmed, and with a
     * `"` turned into `'`. Null for a name that is blank.
     */
    private fun fragmentName(typed: String): String? = typed.trim().replace('"', '\'').takeIf { it.isNotEmpty() }

    /**
     * Makes the state [name] a container state without a fragment: `CONTAINER` goes in front of `STATE`, and nothing is
     * written behind the name. Everything else of the state stays as it is, its `INITIAL` mark, its history, its
     * breakpoints, its tick limit and its transitions. Only a state that the language lets be a container state
     * changes, which is a state that is neither a container state nor a final state and has no `ENTRY` or `DO` block
     * and no inner states.
     */
    public fun makeContainer(text: String, diagram: StatechartDiagram, name: String): DiagramChange? {
        val state = diagram.state(name)?.takeIf(::canContain) ?: return null
        val keyword = text.substring(state.range.start, state.nameRange.start).lastIndexOf(STATE).takeIf { it >= 0 } ?: return null
        val front = state.range.start + keyword
        return DiagramChange(listOf(TextEdit(TextRange(front, front), "$CONTAINER ")))
    }

    /**
     * True if the language lets the state [state] become a container state: it is neither a container state nor a
     * final state, and it has no `ENTRY` or `DO` block and no inner states, none of which a container state may have.
     */
    public fun canContain(state: StateNode): Boolean =
        !state.isContainer && !state.isFinal && !state.isComposite && !state.hasEntry && !state.hasDo

    /**
     * Makes the container state [name] a plain state: `CONTAINER` and `USE` with the fragment go. Since a plain state
     * without inner states has nothing to remember, its history goes too, and a transition or split that leaves it
     * loses the clause `DONE`, as does a join of which it was the only container state among the sources, because the
     * language allows `DONE` only there. Its `INITIAL` mark, its breakpoints and its tick limit stay.
     */
    public fun makePlain(text: String, diagram: StatechartDiagram, name: String): DiagramChange? {
        val state = diagram.state(name)?.takeIf { it.isContainer } ?: return null
        val header = text.substring(state.range.start, state.nameRange.start)
        val words = listOfNotNull(HISTORY.find(header), CONTAINER_WORD.find(header)).map { match ->
            TextEdit(TextRange(state.range.start + match.range.first, state.range.start + match.range.last + 1), "")
        }
        val use = useRemoval(state)
        val done = diagram.transitions
            .filter { it.isDone && name in it.sources && !keepsDone(diagram, it, name) }
            .mapNotNull { removeDone(text, diagram, it.id)?.edits }
            .flatten()
        return DiagramChange(words + listOfNotNull(use) + done)
    }

    /** True if [transition] may keep `DONE` once the state [plain] is no container state: a join with another one. */
    private fun keepsDone(diagram: StatechartDiagram, transition: TransitionEdge, plain: String): Boolean =
        transition.kind == TransitionKind.JOIN &&
            transition.sources.filter { it != plain }.mapNotNull(diagram::state).any { it.isContainer }

    /**
     * Lets the container state [name] run the fragment [fragment], whatever name it is. Only the name after `USE`
     * changes, or `USE` with the name is written behind the name of the state if the text has none yet. A [fragment]
     * that is blank takes `USE` with its name out, and the state is without a fragment again.
     */
    public fun useFragment(text: String, diagram: StatechartDiagram, name: String, fragment: String): DiagramChange? {
        val state = diagram.state(name)?.takeIf { it.isContainer } ?: return null
        val chosen = fragmentName(fragment) ?: return useRemoval(state)?.let { DiagramChange(listOf(it)) }
        if (chosen == state.fragment) return null
        val range = state.fragmentRange
        if (range != null) return DiagramChange(listOf(TextEdit(range, chosen)))
        val at = state.nameRange.end + 1
        return DiagramChange(listOf(TextEdit(TextRange(at, at), " $USE ${StatechartEdits.quoted(chosen)}")))
    }

    /**
     * The edit that takes `USE` with the name of the fragment out of the header of [state], from behind the name of the
     * state to the quote that ends the name of the fragment, or null if the state has no `USE`.
     */
    private fun useRemoval(state: StateNode): TextEdit? =
        state.fragmentRange?.let { TextEdit(TextRange(state.nameRange.end + 1, it.end + 1), "") }

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
