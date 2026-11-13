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

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange

/**
 * Derives the [StatechartDiagram] of a `.kstates` file from its text and its outline. The outline is what the parser
 * of the document layer produces, so the diagram follows the same reading of the text as the editor does and there is
 * no second parser.
 *
 * The outline names the states with their nesting and the transitions with their places in the text. What it leaves
 * out is read from the text of each element: whether a state is the initial one, its history, its breakpoints and its
 * tick limit, which states a transition names on which side of its arrow, whether it is marked `NOT LOOPING`, its
 * breakpoints and its condition.
 *
 * A condition becomes a short label. An `EVENT("switch", "press")` clause reads `switch/press`, a `VALUE("p")` clause
 * reads `p`, and an `EVAL { return x > 1 }` clause reads `[x > 1]`. Several clauses are joined by `&`, since all of
 * them must hold. A long label is cut and ends with an ellipsis.
 */
public object StatechartProjection {

    /** The longest label of a transition in characters. */
    private const val MAX_LABEL = 40

    /** The mark of a label that was cut. */
    private const val ELLIPSIS = "\u2026"

    private val STRING = Regex(""""([^"]*)"""")
    private val NOT_LOOPING = Regex("""\bNOT\s+LOOPING\b""")
    private val CONDITION = Regex("""\bCONDITION\s*\{""")
    private val CLAUSE = Regex("""\b(EVENT|VALUE)\s*\(([^)]*)\)|\bEVAL\s*\{""")
    private val RETURN = Regex("""^return\s+""")
    private val BLANKS = Regex("""\s+""")

    /**
     * The diagram of the state machine that [outline] describes within [text], or null if the outline holds no state
     * machine. [text] must be the text the outline was made from.
     */
    public fun of(text: String, outline: List<OutlineNode>): StatechartDiagram? {
        val machine = outline.firstOrNull { it.kind == SymbolKind.STATEMACHINE } ?: return null
        val states = machine.children.filter { it.kind == SymbolKind.STATE }.map { state(text, it) }
        val counts = HashMap<String, Int>()
        val transitions = machine.children
            .filter { it.kind == SymbolKind.TRANSITION || it.kind == SymbolKind.SPLIT || it.kind == SymbolKind.JOIN }
            .mapIndexed { index, node -> transition(text, node, index + 1, counts) }
        return StatechartDiagram(machine.detail, states, transitions, machine.range)
    }

    /** The state of [node] with the states nested in it. Its history, breakpoints and tick limit are read from [text]. */
    private fun state(text: String, node: OutlineNode): StateNode {
        val open = text.indexOf('{', minOf(node.selectionRange.end + 1, text.length))
        val leading = if (open < 0) LeadingBlocks(null, null) else StateOptionReader.leadingOf(text, open)
        val halts = StateOptionReader.wordsOf(text, leading.breakpoint)
        return StateNode(
            name = node.name,
            isInitial = text.startsWith("INITIAL", node.range.start),
            entryRange = node.children.firstOrNull { it.kind == SymbolKind.ENTRY_BLOCK }?.range,
            doRange = node.children.firstOrNull { it.kind == SymbolKind.DO_BLOCK }?.range,
            children = node.children.filter { it.kind == SymbolKind.STATE }.map { state(text, it) },
            range = node.range,
            nameRange = node.selectionRange,
            history = StateOptionReader.historyOf(text.substring(node.range.start, maxOf(node.range.start, node.selectionRange.start))),
            breakpointRange = leading.breakpoint,
            entryBreakpoint = "ENTRY" in halts,
            exitBreakpoint = "EXIT" in halts,
            tickLimitRange = leading.tickLimit,
            delay = StateOptionReader.delayOf(text, leading.tickLimit),
            timeout = StateOptionReader.timeoutOf(text, leading.tickLimit),
        )
    }

    /**
     * The transition of [node], which is the one at [priority] in the text. [counts] holds how often each combination
     * of kind and states was seen so far, which makes the ids of equal transitions differ.
     */
    private fun transition(text: String, node: OutlineNode, priority: Int, counts: MutableMap<String, Int>): TransitionEdge {
        val whole = text.substring(node.range.start, node.range.end)
        val header = whole.substringBefore('{')
        val kind = when (node.kind) {
            SymbolKind.SPLIT -> TransitionKind.SPLIT
            SymbolKind.JOIN -> TransitionKind.JOIN
            else -> TransitionKind.NORMAL
        }
        val arrow = header.indexOf("->")
        val names = STRING.findAll(header).map { it.range.first to it.groupValues[1] }.toList()
        val (sources, targets) = when {
            arrow >= 0 -> names.filter { it.first < arrow }.map { it.second } to names.filter { it.first > arrow }.map { it.second }
            kind == TransitionKind.JOIN -> names.dropLast(1).map { it.second } to names.takeLast(1).map { it.second }
            else -> names.take(1).map { it.second } to names.drop(1).map { it.second }
        }
        val breakpoint = StateOptionReader.breakpointOf(text, node.range.start + header.length)
        val halts = StateOptionReader.wordsOf(text, breakpoint)
        val signature = StatechartIds.signature(kind, sources, targets)
        val ordinal = counts.merge(signature, 1, Int::plus) ?: 1
        return TransitionEdge(
            id = StatechartIds.transition(signature, ordinal),
            kind = kind,
            sources = sources,
            targets = targets,
            priority = priority,
            label = label(whole),
            notLooping = NOT_LOOPING.containsMatchIn(header),
            range = node.range,
            conditionRange = CONDITION.find(whole)?.let { match ->
                TextRange(node.range.start + match.range.first, node.range.start + closingBrace(whole, match.range.last) + 1)
            },
            breakpointRange = breakpoint,
            startBreakpoint = "START" in halts,
            endBreakpoint = "END" in halts,
        )
    }

    /** The condition of the transition with the text [transition] as a label, or empty if it has none. */
    private fun label(transition: String): String {
        val start = CONDITION.find(transition)?.range?.last ?: return ""
        val body = transition.substring(start + 1, closingBrace(transition, start))
        val clauses = ArrayList<String>()
        var position = 0
        while (true) {
            val match = CLAUSE.find(body, position) ?: break
            if (match.groupValues[1].isNotEmpty()) {
                val arguments = STRING.findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
                clauses += arguments.joinToString("/")
                position = match.range.last + 1
            } else {
                val end = closingBrace(body, match.range.last)
                val code = body.substring(match.range.last + 1, end).trim().replace(BLANKS, " ").replace(RETURN, "")
                clauses += "[$code]"
                position = end + 1
            }
        }
        val label = clauses.joinToString(" & ")
        return if (label.length <= MAX_LABEL) label else label.take(MAX_LABEL - 1) + ELLIPSIS
    }

    /** The index of the brace that closes the one at [open] of [text], or the end of [text] if none does. */
    private fun closingBrace(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return text.length
    }
}
