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
import io.karpfen.ide.notation.diagram.layout.DiagramGrid
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StateHistory
import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes of a state machine that its statechart offers, each as edits of the text. Every function takes the text
 * and the diagram that was derived from exactly that text, and gives the change or null if there is nothing to change.
 *
 * An edit touches only the lines it is about. A new state or transition goes to the end of its block, on a line of
 * its own. A state that moves to another parent keeps its text. So comments, formatting and the code in the blocks of
 * other elements stay as they are.
 */
public object StatechartEdits {

    private val STRING = Regex(""""([^"]*)"""")
    private val LINE_ID = Regex("""^([A-Z]+):(.*)->(.*)#(\d+)$""")
    private const val INITIAL = "INITIAL"
    private const val STATE = "STATE"

    /** A name that no state of [diagram] has: [base], or [base] with the first free number behind it. */
    public fun freshName(diagram: StatechartDiagram, base: String = "state"): String {
        val taken = diagram.allStates().map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
    }

    /**
     * Adds the state [name] at the top level, or nested in the state [parent]. With [at] the new state is drawn with
     * its middle there. With [parentFrame] the state [parent] is drawn at least as large as that box, which keeps a
     * parent that had no nested states before from shrinking around its first one.
     */
    public fun addState(
        text: String,
        diagram: StatechartDiagram,
        parent: String?,
        name: String,
        initial: Boolean,
        at: DiagramPoint? = null,
        parentFrame: DiagramBox? = null,
    ): DiagramChange? {
        if (name.isBlank() || diagram.state(name) != null) return null
        val block = (if (parent == null) statesBlock(text, diagram) else diagram.state(parent)?.let { bodyOf(text, it) }) ?: return null
        val statement = (if (initial) "$INITIAL " else "") + "$STATE ${quoted(name)} { }"
        return DiagramChange(listOf(TextBlocks.append(text, block, statement)), arranges = at != null) { vis ->
            vis.copy(
                boxes = if (at == null) vis.boxes else vis.boxes + (name to DiagramGrid.snap(at)),
                frames = if (parent == null || parentFrame == null) vis.frames else vis.frames + (parent to parentFrame),
            )
        }
    }

    /** Adds a transition without a condition from the state [source] to the state [target], as the last transition. */
    public fun addTransition(text: String, diagram: StatechartDiagram, source: String, target: String): DiagramChange? {
        if (diagram.state(source) == null || diagram.state(target) == null) return null
        val block = transitionsBlock(text, diagram) ?: return null
        return DiagramChange(listOf(TextBlocks.append(text, block, "TRANSITION ${quoted(source)} -> ${quoted(target)} { }")))
    }

    /**
     * Renames the state [old] to [new], in its declaration and in every transition that names it. The state keeps its
     * place in the layout, and so do the waypoints and labels of its transitions.
     */
    public fun rename(text: String, diagram: StatechartDiagram, old: String, new: String): DiagramChange? {
        val state = diagram.state(old) ?: return null
        val name = new.trim().replace('"', '\'')
        if (name.isEmpty() || name == old || diagram.state(name) != null) return null
        val uses = diagram.transitions.flatMap { transition ->
            val header = text.substring(transition.range.start, transition.range.end).substringBefore('{')
            STRING.findAll(header).filter { it.groupValues[1] == old }.map { match ->
                TextEdit(TextRange(transition.range.start + match.range.first + 1, transition.range.start + match.range.last), name)
            }
        }
        return DiagramChange(listOf(TextEdit(state.nameRange, name)) + uses) { vis ->
            fun <V> Map<String, V>.boxes(): Map<String, V> = mapKeys { if (it.key == old) name else it.key }
            fun <V> Map<String, V>.lines(): Map<String, V> = mapKeys { renamedLine(it.key, old, name) }
            VisLayout(vis.boxes.boxes(), vis.bars.lines(), vis.waypoints.lines(), vis.labels.lines(), vis.frames.boxes())
        }
    }

    /** Deletes the state [name] with the states nested in it and every transition that names one of them. */
    public fun deleteState(text: String, diagram: StatechartDiagram, name: String): DiagramChange? {
        val state = diagram.state(name) ?: return null
        val gone = state.withDescendants().map { it.name }.toSet()
        val transitions = diagram.transitions.filter { (it.sources + it.targets).any(gone::contains) }
        return DiagramChange(listOf(TextBlocks.removal(text, state.range)) + transitions.map { TextBlocks.removal(text, it.range) })
    }

    /** Deletes the transition with the id [id]. */
    public fun deleteTransition(text: String, diagram: StatechartDiagram, id: String): DiagramChange? {
        val transition = diagram.transitions.firstOrNull { it.id == id } ?: return null
        return DiagramChange(listOf(TextBlocks.removal(text, transition.range)))
    }

    /**
     * Makes the state [name] the initial state of its level. A state of the same level that was the initial one is an
     * ordinary state afterwards. Both keep their histories.
     */
    public fun makeInitial(text: String, diagram: StatechartDiagram, name: String): DiagramChange? {
        val state = diagram.state(name)?.takeIf { !it.isInitial } ?: return null
        val siblings = diagram.parentOf(name)?.children ?: diagram.states
        val demoted = siblings.filter { it.isInitial }.map { sibling ->
            val word = sibling.range.start + INITIAL.length
            val next = (word until sibling.nameRange.start).firstOrNull { !text[it].isWhitespace() } ?: word
            TextEdit(TextRange(sibling.range.start, next), "")
        }
        return DiagramChange(demoted + TextEdit(TextRange(state.range.start, state.range.start), "$INITIAL "))
    }

    /**
     * Moves the state [name] with the states nested in it into the state [parent], or to the top level for null. The
     * state cannot move into itself or into a state nested in it.
     */
    public fun moveTo(text: String, diagram: StatechartDiagram, name: String, parent: String?): DiagramChange? {
        val state = diagram.state(name) ?: return null
        if (diagram.parentOf(name)?.name == parent) return null
        if (parent != null && state.withDescendants().any { it.name == parent }) return null
        val block = (if (parent == null) statesBlock(text, diagram) else diagram.state(parent)?.let { bodyOf(text, it) }) ?: return null
        return disjoint(listOf(TextBlocks.removal(text, state.range), TextBlocks.append(text, block, TextBlocks.lifted(text, state.range))))
    }

    /**
     * Gives the state [name] an empty block of the kind [keyword], which is `ENTRY` or `DO`. The entry block comes
     * after the breakpoints and the tick limit of the state and the do block after the entry block, as the language
     * wants it.
     */
    public fun addBlock(text: String, diagram: StatechartDiagram, name: String, keyword: String): DiagramChange? {
        val state = diagram.state(name) ?: return null
        val isEntry = keyword == ENTRY
        if (if (isEntry) state.hasEntry else state.hasDo) return null
        val body = bodyOf(text, state) ?: return null
        val leading = state.tickLimitRange ?: state.breakpointRange
        return DiagramChange(listOf(TextBlocks.insert(text, body, "$keyword { }", after = if (isEntry) leading else state.entryRange ?: leading)))
    }

    /**
     * Gives the state [name] the history [history], or takes its history away for [StateHistory.NONE]. The words of a
     * history stand between `INITIAL` and `STATE`.
     */
    public fun setHistory(text: String, diagram: StatechartDiagram, name: String, history: StateHistory): DiagramChange? {
        val state = diagram.state(name)?.takeIf { it.history != history } ?: return null
        val header = text.substring(state.range.start, state.nameRange.start)
        val words = when (history) {
            StateHistory.NONE -> ""
            StateHistory.SHALLOW -> "SHALLOW HISTORY "
            StateHistory.DEEP -> "DEEP HISTORY "
        }
        val written = HISTORY.find(header)
        val edit = if (written != null) {
            TextEdit(TextRange(state.range.start + written.range.first, state.range.start + written.range.last + 1), words)
        } else {
            val keyword = header.lastIndexOf(STATE).takeIf { it >= 0 } ?: return null
            (state.range.start + keyword).let { TextEdit(TextRange(it, it), words) }
        }
        return DiagramChange(listOf(edit))
    }

    /**
     * Sets the breakpoints of the state [name]: one before it is entered for [entry], one before it is left for
     * [exit]. A state without either has no `BREAKPOINT` block.
     */
    public fun setStateBreakpoints(text: String, diagram: StatechartDiagram, name: String, entry: Boolean, exit: Boolean): DiagramChange? {
        val state = diagram.state(name) ?: return null
        if (state.entryBreakpoint == entry && state.exitBreakpoint == exit) return null
        val body = bodyOf(text, state) ?: return null
        return breakpoints(text, body, state.breakpointRange, listOfNotNull("ENTRY".takeIf { entry }, "EXIT".takeIf { exit }))
    }

    /**
     * Sets the breakpoints of the transition [id]: one before it changes the active states for [start], one after it
     * for [end]. A transition without either has no `BREAKPOINT` block.
     */
    public fun setTransitionBreakpoints(text: String, diagram: StatechartDiagram, id: String, start: Boolean, end: Boolean): DiagramChange? {
        val transition = diagram.transitions.firstOrNull { it.id == id } ?: return null
        if (transition.startBreakpoint == start && transition.endBreakpoint == end) return null
        val body = TextBlocks.blockFrom(text, transition.range.start) ?: return null
        return breakpoints(text, body, transition.breakpointRange, listOfNotNull("START".takeIf { start }, "END".takeIf { end }))
    }

    /**
     * Gives the state [name] a tick limit with a delay of one tick, which the user then edits in its code cell. The
     * tick limit comes after the breakpoints of the state and before its actions.
     */
    public fun addTickLimit(text: String, diagram: StatechartDiagram, name: String): DiagramChange? {
        val state = diagram.state(name)?.takeIf { !it.hasTickLimit } ?: return null
        val body = bodyOf(text, state) ?: return null
        return DiagramChange(listOf(TextBlocks.insert(text, body, "TICK LIMIT { DELAY: 1 TICK }", after = state.breakpointRange)))
    }

    /**
     * The change that makes the `BREAKPOINT` block of the body [body] name [words]. The block [written] is rewritten,
     * or taken out if no word is left. Without a block one is put first into the body, where the language wants it.
     */
    private fun breakpoints(text: String, body: TextRange, written: TextRange?, words: List<String>): DiagramChange? {
        val block = "BREAKPOINT { ${words.joinToString(" ")} }"
        val edit = when {
            written != null && words.isEmpty() -> TextBlocks.removal(text, written)
            written != null -> TextEdit(written, block)
            words.isEmpty() -> return null
            else -> TextBlocks.insert(text, body, block, after = null)
        }
        return DiagramChange(listOf(edit))
    }

    /**
     * Marks the transition [id] as `NOT LOOPING`, or takes the mark off if it has it. The mark stands behind the
     * states of the transition.
     */
    public fun toggleLooping(text: String, diagram: StatechartDiagram, id: String): DiagramChange? {
        val transition = diagram.transitions.firstOrNull { it.id == id } ?: return null
        val body = TextBlocks.blockFrom(text, transition.range.start) ?: return null
        val header = text.substring(transition.range.start, body.start)
        val mark = NOT_LOOPING.find(header)
        val edit = if (mark != null) {
            TextEdit(TextRange(transition.range.start + mark.range.first, transition.range.start + mark.range.last + 1), "")
        } else {
            (transition.range.start + header.trimEnd().length).let { TextEdit(TextRange(it, it), " NOT LOOPING") }
        }
        return DiagramChange(listOf(edit))
    }

    /**
     * Moves the transition [id] one place earlier in the text, or one place later. The order of the transitions is
     * their priority: of several that can fire, the first one does.
     */
    public fun move(text: String, diagram: StatechartDiagram, id: String, earlier: Boolean): DiagramChange? {
        val index = diagram.transitions.indexOfFirst { it.id == id }
        val other = diagram.transitions.getOrNull(if (earlier) index - 1 else index + 1) ?: return null
        val moved = diagram.transitions.getOrNull(index) ?: return null
        fun textOf(range: TextRange): String = text.substring(range.start, range.end)
        return disjoint(listOf(TextEdit(moved.range, textOf(other.range)), TextEdit(other.range, textOf(moved.range))))
    }

    /**
     * Adds a split from the state [source] into the states [targets], which must be two or more, as the last
     * transition.
     */
    public fun addSplit(text: String, diagram: StatechartDiagram, source: String, targets: List<String>): DiagramChange? {
        if (targets.size < 2 || (targets + source).any { diagram.state(it) == null }) return null
        val block = transitionsBlock(text, diagram) ?: return null
        return DiagramChange(listOf(TextBlocks.append(text, block, "SPLIT ${quoted(source)} -> ${targets.joinToString(", ", transform = ::quoted)} { }")))
    }

    /**
     * Adds a join from the states [sources], which must be two or more, into the state [target], as the last
     * transition.
     */
    public fun addJoin(text: String, diagram: StatechartDiagram, sources: List<String>, target: String): DiagramChange? {
        if (sources.size < 2 || (sources + target).any { diagram.state(it) == null }) return null
        val block = transitionsBlock(text, diagram) ?: return null
        return DiagramChange(listOf(TextBlocks.append(text, block, "JOIN ${sources.joinToString(", ", transform = ::quoted)} -> ${quoted(target)} { }")))
    }

    /** Gives the split [id] one more target, or the join [id] one more source, which is the state [name]. */
    public fun addEnd(text: String, diagram: StatechartDiagram, id: String, name: String): DiagramChange? {
        val transition = diagram.transitions.firstOrNull { it.id == id } ?: return null
        if (diagram.state(name) == null || name in transition.sources + transition.targets) return null
        val body = TextBlocks.blockFrom(text, transition.range.start) ?: return null
        val header = text.substring(transition.range.start, body.start)
        val arrow = header.indexOf("->")
        val names = STRING.findAll(header).toList()
        val last = when (transition.kind) {
            TransitionKind.SPLIT -> names.lastOrNull()
            TransitionKind.JOIN -> names.lastOrNull { it.range.last < arrow }
            TransitionKind.NORMAL -> null
        } ?: return null
        val at = transition.range.start + last.range.last + 1
        return DiagramChange(listOf(TextEdit(TextRange(at, at), ", ${quoted(name)}")))
    }

    /**
     * The macros of the state machine as a code cell: what stands between the braces of its `MACROS` block. A state
     * machine without that block has an empty cell, and code written into it makes the block.
     */
    public fun macros(text: String, diagram: StatechartDiagram): DiagramCell? {
        val machine = TextBlocks.blockFrom(text, diagram.range.start) ?: return null
        val block = TextBlocks.blockNamed(text, MACROS, machine)
        return DiagramCell("macros", "Macros", "", DiagramBox(0f, 0f, 0f, 0f), block?.let { TextBlocks.inner(text, it) }.orEmpty()) { code ->
            when {
                block != null -> TextEdit(block, TextBlocks.braced(code, TextBlocks.indentOf(text, block.start)))
                    .takeIf { it.newText != text.substring(block.start, block.end) }
                code.isBlank() -> null
                else -> TextBlocks.append(text, machine, "$MACROS ${TextBlocks.braced(code, "")}")
            }?.let { DiagramChange(listOf(it)) }
        }
    }

    private const val MACROS = "MACROS"
    private val HISTORY = Regex("""\b(SHALLOW|DEEP)\s+HISTORY\s+""")
    private val NOT_LOOPING = Regex("""\s+NOT\s+LOOPING\b""")

    /** The keyword of the entry block of a state. */
    public const val ENTRY: String = "ENTRY"

    /** The keyword of the do block of a state. */
    public const val DO: String = "DO"

    /** The braces of the state [state] with everything between them. The search starts behind the quote that ends its name. */
    private fun bodyOf(text: String, state: StateNode): TextRange? = TextBlocks.blockFrom(text, state.nameRange.end + 1)

    private fun statesBlock(text: String, diagram: StatechartDiagram): TextRange? = blockOf(text, diagram, "STATES")

    private fun transitionsBlock(text: String, diagram: StatechartDiagram): TextRange? = blockOf(text, diagram, "TRANSITIONS")

    /** The block that follows [keyword] directly inside the state machine. */
    private fun blockOf(text: String, diagram: StatechartDiagram, keyword: String): TextRange? =
        TextBlocks.blockFrom(text, diagram.range.start)?.let { TextBlocks.blockNamed(text, keyword, it) }

    /** [name] as a string literal of the language. A double quote inside it becomes a single one. */
    private fun quoted(name: String): String = "\"" + name.trim().replace('"', '\'') + "\""

    /** The id of a transition with the state [old] renamed to [new], or [id] as it is if it is no such id. */
    private fun renamedLine(id: String, old: String, new: String): String {
        val match = LINE_ID.matchEntire(id) ?: return id
        fun names(list: String): String = list.split(",").joinToString(",") { if (it == old) new else it }
        return "${match.groupValues[1]}:${names(match.groupValues[2])}->${names(match.groupValues[3])}#${match.groupValues[4]}"
    }

    /** A change of [edits], or null if two of them overlap, in which case nothing is changed. */
    private fun disjoint(edits: List<TextEdit>): DiagramChange? {
        val ordered = edits.sortedBy { it.range.start }
        return if (ordered.zipWithNext().any { (first, second) -> first.range.end > second.range.start }) null else DiagramChange(ordered)
    }
}
