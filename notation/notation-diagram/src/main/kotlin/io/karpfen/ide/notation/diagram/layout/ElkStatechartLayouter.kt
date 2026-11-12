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
package io.karpfen.ide.notation.diagram.layout

import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TransitionEdge
import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import org.eclipse.elk.alg.layered.options.CycleBreakingStrategy
import org.eclipse.elk.alg.layered.options.LayerConstraint
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * A [StatechartLayouter] on the layered algorithm of the Eclipse Layout Kernel. It builds the kernel's graph from the
 * diagram, lets the kernel place it, and reads the positions back.
 *
 * A state becomes a node, nested in the node of its parent. A state with nested states gets padding at its top for
 * its name. An initial state gets a small marker node with a line to the state. The marker is kept in the first
 * layer, and where transitions form a circle the order of the text decides which one runs against the flow. So the
 * drawing starts at the initial state and reads from the top down, in the order the states are written. A normal
 * transition becomes one edge.
 * A split or a join becomes a bar node with one edge per state it names, and the label sits on the edge that leads
 * into the bar of a split or out of the bar of a join.
 *
 * A transition that names a state the diagram does not have is left out of the layout. The text is checked
 * elsewhere, and the drawing shows what can be drawn.
 */
internal class ElkStatechartLayouter : StatechartLayouter {

    override fun layout(diagram: StatechartDiagram, text: TextMeasurer): StatechartLayout {
        if (diagram.states.isEmpty()) return StatechartLayout.EMPTY
        val graph = Graph(text)
        diagram.states.forEach { graph.addState(it, graph.root) }
        diagram.transitions.forEach(graph::addTransition)
        ElkKernel.layout(graph.root)
        return graph.read()
    }

    /** The kernel's graph of one diagram, with what each of its nodes and edges stands for. */
    private class Graph(private val measurer: TextMeasurer) {
        val root: ElkNode = ElkGraphUtil.createGraph().apply {
            setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
            setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
            setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
            setProperty(CoreOptions.PADDING, ElkPadding(StatechartMetrics.MARGIN.toDouble()))
            setProperty(CoreOptions.SPACING_NODE_NODE, NODE_SPACING)
            setProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY, CycleBreakingStrategy.MODEL_ORDER)
            setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
            setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, LAYER_SPACING)
            setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, EDGE_SPACING)
        }
        private val states = LinkedHashMap<String, ElkNode>()
        private val markers = LinkedHashMap<String, ElkNode>()
        private val bars = LinkedHashMap<String, ElkNode>()
        private val edges = ArrayList<Line>()

        /** An edge of the kernel with what it stands for. */
        private class Line(val edge: ElkEdge, val kind: RouteKind, val transitionId: String?)

        /** Adds [state] with the states nested in it to [parent]. The first declaration of a name counts. */
        fun addState(state: StateNode, parent: ElkNode) {
            if (state.name in states) return
            val marker = if (state.isInitial) ElkGraphUtil.createNode(parent) else null
            val node = ElkGraphUtil.createNode(parent)
            val nameWidth = measurer.widthOf(state.name) + 2 * StatechartMetrics.NAME_PADDING
            if (state.isComposite) {
                val inner = StatechartMetrics.INNER_MARGIN.toDouble()
                node.setProperty(CoreOptions.PADDING, ElkPadding(StatechartMetrics.HEADER + inner, inner, inner, inner))
                node.setProperty(CoreOptions.NODE_SIZE_MINIMUM, org.eclipse.elk.core.math.KVector(nameWidth.toDouble(), StatechartMetrics.HEADER.toDouble()))
                node.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, java.util.EnumSet.of(org.eclipse.elk.core.options.SizeConstraint.MINIMUM_SIZE))
            } else {
                node.width = StatechartMetrics.leafWidth(state, measurer).toDouble()
                node.height = StatechartMetrics.leafHeight(state).toDouble()
            }
            states[state.name] = node
            if (marker != null) {
                marker.width = StatechartMetrics.MARKER.toDouble()
                marker.height = StatechartMetrics.MARKER.toDouble()
                marker.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, LayerConstraint.FIRST)
                markers[state.name] = marker
                edges += Line(ElkGraphUtil.createSimpleEdge(marker, node), RouteKind.INITIAL, null)
            }
            state.children.forEach { addState(it, node) }
        }

        /** Adds the edges of [transition], and its bar if it is a split or a join. */
        fun addTransition(transition: TransitionEdge) {
            val sources = transition.sources.mapNotNull(states::get)
            val targets = transition.targets.mapNotNull(states::get)
            if (sources.size != transition.sources.size || targets.size != transition.targets.size) return
            if (sources.isEmpty() || targets.isEmpty()) return
            if (transition.kind == TransitionKind.NORMAL) {
                edges += Line(labelled(ElkGraphUtil.createSimpleEdge(sources.first(), targets.first()), transition), RouteKind.TRANSITION, transition.id)
                return
            }
            val bar = ElkGraphUtil.createNode(commonContainer(sources + targets)).apply {
                width = StatechartMetrics.BAR_LENGTH.toDouble()
                height = StatechartMetrics.BAR_THICKNESS.toDouble()
            }
            bars[transition.id] = bar
            sources.forEachIndexed { index, source ->
                val edge = ElkGraphUtil.createSimpleEdge(source, bar)
                if (transition.kind == TransitionKind.SPLIT && index == 0) labelled(edge, transition)
                edges += Line(edge, RouteKind.INTO_BAR, transition.id)
            }
            targets.forEachIndexed { index, target ->
                val edge = ElkGraphUtil.createSimpleEdge(bar, target)
                if (transition.kind == TransitionKind.JOIN && index == 0) labelled(edge, transition)
                edges += Line(edge, RouteKind.OUT_OF_BAR, transition.id)
            }
        }

        /** Gives [edge] a label as large as the caption of [transition] needs, and returns the edge. */
        private fun labelled(edge: ElkEdge, transition: TransitionEdge): ElkEdge {
            val caption = transition.caption
            ElkGraphUtil.createLabel(caption, edge).apply {
                width = (measurer.widthOf(caption) + StatechartMetrics.LABEL_PADDING + StatechartMetrics.CELL_ROOM).toDouble()
                height = StatechartMetrics.LABEL_HEIGHT.toDouble()
            }
            return edge
        }

        /** The innermost node that contains all of [nodes]. That is where the bar of a split or join belongs. */
        private fun commonContainer(nodes: List<ElkNode>): ElkNode {
            val chains = nodes.map { node -> generateSequence(node.parent) { it.parent }.toList().reversed() }
            var common = root
            for (depth in 0 until chains.minOf { it.size }) {
                val candidate = chains.first()[depth]
                if (chains.all { it[depth] === candidate }) common = candidate else break
            }
            return common
        }

        /** The layout the kernel computed, in absolute positions. */
        fun read(): StatechartLayout = StatechartLayout(
            states = states.mapValues { boxOf(it.value) },
            initialMarkers = markers.mapValues { boxOf(it.value) },
            bars = bars.mapValues { boxOf(it.value) },
            routes = edges.mapNotNull(::routeOf),
            width = root.width.toFloat(),
            height = root.height.toFloat(),
            headerHeight = StatechartMetrics.HEADER,
        )

        private fun boxOf(node: ElkNode): DiagramBox = ElkKernel.boxOf(node, root)

        /** The route of [line], or null if the kernel gave the edge no path. */
        private fun routeOf(line: Line): EdgeRoute? {
            val points = ElkKernel.pointsOf(line.edge, root) ?: return null
            return EdgeRoute(line.kind, line.transitionId, points, ElkKernel.labelOf(line.edge, root))
        }
    }

    private companion object {
        /** The distance between two states of one layer. */
        const val NODE_SPACING = 36.0

        /** The distance between two layers of states. */
        const val LAYER_SPACING = 48.0

        /** The distance between a line and a state of the next layer. */
        const val EDGE_SPACING = 18.0
    }
}
