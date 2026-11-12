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

/**
 * The layout of a statechart that was arranged by hand. It draws every element where the [VisLayout] says and does
 * no automatic layout, with one exception: an element the [VisLayout] does not know is new in the text and gets a
 * place of its own.
 *
 * The rules:
 * - A state without nested states sits with its middle on its stored point. Its size follows from its name.
 * - A state with nested states has no stored point. Its box surrounds the states inside it, with room for its name
 *   at the top.
 * - A new state is placed to the right of its placed siblings, at their top. Where no sibling is placed, the row
 *   starts at the top left of the parent, or of the drawing.
 * - The marker of an initial state sits above that state.
 * - The bar of a split or join sits on its stored point, or else in the middle between the states it connects.
 * - A normal transition runs from the border of its source through its waypoints to the border of its target. Without
 *   waypoints it is a straight line. Where a waypoint or the other state is lined up with a state, the line meets
 *   that state at a right angle. A transition between a state and one nested in it runs straight down from the top
 *   border of the outer state to the top of the inner one. A transition from a state to itself gets a small loop at
 *   its right side.
 * - The caption of a transition stands beside the middle of its line.
 */
public object ManualStatechartLayout {

    /** The distance between a line and the caption beside it. */
    private const val LABEL_DISTANCE = 4f

    /** How far the loop of a transition from a state to itself reaches to the right of the state. */
    private const val LOOP_REACH = 30f

    /** Half the height of the loop of a transition from a state to itself. */
    private const val LOOP_HALF_HEIGHT = 10f

    /** The layout of [diagram] as [vis] arranges it, with texts as wide as [text] says. */
    public fun of(diagram: StatechartDiagram, vis: VisLayout, text: TextMeasurer): StatechartLayout {
        if (diagram.states.isEmpty()) return StatechartLayout.EMPTY
        val placement = Placement(vis, text)
        placement.place(diagram.states, DiagramPoint(StatechartMetrics.MARGIN, StatechartMetrics.MARGIN))
        val boxes = placement.boxes
        val markers = LinkedHashMap<String, DiagramBox>()
        val bars = LinkedHashMap<String, DiagramBox>()
        val routes = ArrayList<EdgeRoute>()
        for (state in diagram.allStates().filter { it.isInitial }) {
            val box = boxes[state.name] ?: continue
            if (state.name in markers) continue
            val size = StatechartMetrics.MARKER
            val marker = DiagramBox(box.center.x - size / 2f, box.y - StatechartMetrics.MARKER_GAP - size, size, size)
            markers[state.name] = marker
            routes += EdgeRoute(RouteKind.INITIAL, null, listOf(DiagramPoint(box.center.x, marker.bottom), DiagramPoint(box.center.x, box.y)))
        }
        for (transition in diagram.transitions) {
            val sources = transition.sources.mapNotNull(boxes::get)
            val targets = transition.targets.mapNotNull(boxes::get)
            if (sources.size != transition.sources.size || targets.size != transition.targets.size) continue
            if (sources.isEmpty() || targets.isEmpty()) continue
            if (transition.kind == TransitionKind.NORMAL) {
                routes += normal(transition, sources.first(), targets.first(), vis.waypoints[transition.id].orEmpty(), text)
            } else {
                val bar = barOf(vis.bars[transition.id] ?: StatechartMetrics.snap(centroid(sources + targets)))
                bars[transition.id] = bar
                routes += forked(transition, sources, targets, bar, text)
            }
        }
        val layout = StatechartLayout(boxes, markers, bars, routes, 0f, 0f, StatechartMetrics.HEADER)
        val bounds = layout.bounds
        return layout.copy(width = bounds.right + StatechartMetrics.MARGIN, height = bounds.bottom + StatechartMetrics.MARGIN)
    }

    /** The box of a bar with its middle at [center]. */
    internal fun barOf(center: DiagramPoint): DiagramBox = DiagramBox(
        center.x - StatechartMetrics.BAR_LENGTH / 2f,
        center.y - StatechartMetrics.BAR_THICKNESS / 2f,
        StatechartMetrics.BAR_LENGTH,
        StatechartMetrics.BAR_THICKNESS,
    )

    private fun centroid(boxes: List<DiagramBox>): DiagramPoint =
        DiagramPoint(boxes.map { it.center.x }.average().toFloat(), boxes.map { it.center.y }.average().toFloat())

    /** The line of a normal transition from [source] through [waypoints] to [target], with its caption. */
    private fun normal(
        transition: TransitionEdge,
        source: DiagramBox,
        target: DiagramBox,
        waypoints: List<DiagramPoint>,
        text: TextMeasurer,
    ): EdgeRoute {
        val through = when {
            waypoints.isNotEmpty() -> waypoints
            source == target -> listOf(
                DiagramPoint(source.right + LOOP_REACH, source.center.y - LOOP_HALF_HEIGHT),
                DiagramPoint(source.right + LOOP_REACH, source.center.y + LOOP_HALF_HEIGHT),
            )
            else -> emptyList()
        }
        val points = when {
            through.isEmpty() && Geometry.contains(source, target) ->
                listOf(DiagramPoint(target.center.x, source.y), DiagramPoint(target.center.x, target.y))
            through.isEmpty() && Geometry.contains(target, source) ->
                listOf(DiagramPoint(source.center.x, source.y), DiagramPoint(source.center.x, target.y))
            else -> {
                val start = Geometry.anchor(source, through.firstOrNull() ?: target.center)
                listOf(start) + through + Geometry.anchor(target, through.lastOrNull() ?: start)
            }
        }
        return EdgeRoute(RouteKind.TRANSITION, transition.id, points, labelOn(points, transition, text))
    }

    /** The lines of a split or join through its [bar]. The caption sits on the line of the single state. */
    private fun forked(
        transition: TransitionEdge,
        sources: List<DiagramBox>,
        targets: List<DiagramBox>,
        bar: DiagramBox,
        text: TextMeasurer,
    ): List<EdgeRoute> {
        val into = sources.mapIndexed { index, source ->
            val start = Geometry.anchor(source, bar.center)
            val points = listOf(start, Geometry.anchor(bar, start))
            val label = if (transition.kind == TransitionKind.SPLIT && index == 0) labelOn(points, transition, text) else null
            EdgeRoute(RouteKind.INTO_BAR, transition.id, points, label)
        }
        val out = targets.mapIndexed { index, target ->
            val end = Geometry.anchor(target, bar.center)
            val points = listOf(Geometry.anchor(bar, end), end)
            val label = if (transition.kind == TransitionKind.JOIN && index == 0) labelOn(points, transition, text) else null
            EdgeRoute(RouteKind.OUT_OF_BAR, transition.id, points, label)
        }
        return into + out
    }

    /**
     * The box of the caption of [transition] at the middle of the line through [points]. The caption stands beside
     * the line and not on it: to the right of a part that runs up and down, and above a part that runs sideways.
     */
    private fun labelOn(points: List<DiagramPoint>, transition: TransitionEdge, text: TextMeasurer): DiagramBox {
        val middle = Geometry.middle(points)
        val width = text.widthOf(transition.caption) + StatechartMetrics.LABEL_PADDING
        val height = StatechartMetrics.LABEL_HEIGHT
        return if (Geometry.isUprightAtMiddle(points)) {
            DiagramBox(middle.x + LABEL_DISTANCE, middle.y - height / 2f, width, height)
        } else {
            DiagramBox(middle.x - width / 2f, middle.y - LABEL_DISTANCE - height, width, height)
        }
    }

    /** Places the states: the stored ones where they are stored, the new ones next to them. */
    private class Placement(private val vis: VisLayout, private val text: TextMeasurer) {
        val boxes = LinkedHashMap<String, DiagramBox>()

        /**
         * Places [siblings], the states of one level. Returns the box around all of them, or null if there are none.
         * [origin] is the top left corner where the row of new states starts if no sibling has a stored place.
         */
        fun place(siblings: List<StateNode>, origin: DiagramPoint): DiagramBox? {
            val states = siblings.distinctBy { it.name }.filter { it.name !in boxes }
            val (stored, new) = states.partition(::isStored)
            val placed = stored.map(::placeStored).toMutableList()
            var left = placed.maxOfOrNull { it.right + StatechartMetrics.GAP } ?: origin.x
            val top = placed.minOfOrNull { it.y } ?: origin.y
            for (state in new) {
                val box = placeNew(state, DiagramPoint(left, top))
                placed += box
                left = box.right + StatechartMetrics.GAP
            }
            return placed.reduceOrNull(DiagramBox::union)
        }

        /** True if [state] or a state nested in it has a stored place. */
        private fun isStored(state: StateNode): Boolean =
            if (state.isComposite) state.children.any(::isStored) else state.name in vis.states

        private fun placeStored(state: StateNode): DiagramBox {
            val box = if (state.isComposite) {
                surrounding(state, checkNotNull(place(state.children, DiagramPoint(0f, 0f))))
            } else {
                leaf(state, vis.states.getValue(state.name))
            }
            boxes[state.name] = box
            return box
        }

        private fun placeNew(state: StateNode, topLeft: DiagramPoint): DiagramBox {
            val box = if (state.isComposite) {
                val inner = DiagramPoint(
                    topLeft.x + StatechartMetrics.INNER_MARGIN,
                    topLeft.y + StatechartMetrics.HEADER + StatechartMetrics.INNER_MARGIN,
                )
                surrounding(state, checkNotNull(place(state.children, inner)))
            } else {
                val width = StatechartMetrics.leafWidth(state, text)
                val height = StatechartMetrics.leafHeight(state)
                leaf(state, StatechartMetrics.snap(DiagramPoint(topLeft.x + width / 2f, topLeft.y + height / 2f)))
            }
            boxes[state.name] = box
            return box
        }

        /** The box of the state [state] without nested states, with its middle at [center]. */
        private fun leaf(state: StateNode, center: DiagramPoint): DiagramBox {
            val width = StatechartMetrics.leafWidth(state, text)
            val height = StatechartMetrics.leafHeight(state)
            return DiagramBox(center.x - width / 2f, center.y - height / 2f, width, height)
        }

        /** The box of [state] around the box [inner] of its nested states, with room for its name. */
        private fun surrounding(state: StateNode, inner: DiagramBox): DiagramBox {
            val margin = StatechartMetrics.INNER_MARGIN
            val x = inner.x - margin
            val y = inner.y - margin - StatechartMetrics.HEADER
            val width = maxOf(inner.width + 2 * margin, text.widthOf(state.name) + 2 * StatechartMetrics.NAME_PADDING)
            return DiagramBox(x, y, width, inner.bottom + margin - y)
        }
    }
}
