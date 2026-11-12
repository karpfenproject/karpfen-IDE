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
package io.karpfen.ide.ui.diagram

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.VisEditing
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram

/**
 * The layout by hand of one statechart while the user works on it. It holds the [vis], which is null as long as the
 * statechart is laid out automatically, and turns the gestures of the canvas into changes of it.
 *
 * The first change by hand freezes the layout on screen, so every element keeps its place and from then on nothing is
 * laid out automatically any more, except elements that are new in the text. A drag moves a waypoint, the bar of a
 * split or join, or a state, whichever lies under the pointer, in this order. A state with nested states takes them
 * along. A secondary click on a waypoint removes it, and one on the line of a transition adds a waypoint there.
 *
 * After every finished change, [save] gets the layout to write. What the text no longer has is dropped from it first.
 * [loaded] takes in what was read from the companion file. It is ignored while the user drags and while a change of
 * the user is still on its way to the file, so a reload never undoes what the user just did.
 *
 * All points are in the units of the diagram. The object belongs to the UI thread.
 */
internal class StatechartEditing(private val save: (VisLayout) -> Unit) {

    /** The layout by hand, or null while the statechart is laid out automatically. */
    var vis: VisLayout? by mutableStateOf(null)
        private set

    /** True while the user drags an element. The canvas shows the grid then. */
    var isDragging: Boolean by mutableStateOf(false)
        private set

    private var drag: Drag? = null
    private var unsaved: VisLayout? = null

    /** What is being dragged, the layout it started from, and what [vis] was before, to go back if nothing moved. */
    private class Drag(val target: Target, val base: VisLayout, val previous: VisLayout?, val start: DiagramPoint)

    private sealed interface Target {
        data class State(val name: String) : Target
        data class Bar(val id: String) : Target
        data class Waypoint(val id: String, val index: Int) : Target
    }

    /**
     * Starts a drag at [point] of the statechart [diagram] drawn as [layout]. Returns true if an element was grabbed,
     * and false if the pointer is on the empty canvas, which the caller then moves as a whole.
     */
    fun startDrag(diagram: StatechartDiagram, layout: StatechartLayout, point: DiagramPoint): Boolean {
        val current = vis
        val target = current?.let { VisEditing.waypointAt(diagram, it, point) }?.let { Target.Waypoint(it.first, it.second) }
            ?: layout.barAt(point)?.let(Target::Bar)
            ?: layout.stateAt(point)?.let(Target::State)
            ?: return false
        val base = current ?: VisEditing.freeze(diagram, layout)
        drag = Drag(target, base, current, point)
        vis = base
        isDragging = true
        return true
    }

    /** Moves the dragged element so that the pointer is at [point]. */
    fun dragTo(diagram: StatechartDiagram, point: DiagramPoint) {
        val current = drag ?: return
        vis = when (val target = current.target) {
            is Target.State -> VisEditing.moveState(diagram, current.base, target.name, DiagramPoint(point.x - current.start.x, point.y - current.start.y))
            is Target.Bar -> VisEditing.moveBar(current.base, target.id, point)
            is Target.Waypoint -> VisEditing.moveWaypoint(current.base, target.id, target.index, point)
        }
    }

    /** Ends the drag. A drag that moved nothing leaves everything as it was, also the automatic layout. */
    fun endDrag(diagram: StatechartDiagram) {
        val current = drag ?: return
        drag = null
        isDragging = false
        val result = vis
        if (result == null || result == current.base) vis = current.previous else store(diagram, result)
    }

    /**
     * Handles a secondary click at [point]. On a waypoint it removes the waypoint. On the line of a transition it
     * adds a waypoint there. Returns true if the click changed the layout.
     */
    fun secondaryClick(diagram: StatechartDiagram, layout: StatechartLayout, point: DiagramPoint): Boolean {
        val current = vis
        val waypoint = current?.let { VisEditing.waypointAt(diagram, it, point) }
        if (current != null && waypoint != null) {
            store(diagram, VisEditing.removeWaypoint(current, waypoint.first, waypoint.second))
            return true
        }
        val line = layout.transitionAt(point) ?: return false
        val id = line.transitionId ?: return false
        store(diagram, VisEditing.addWaypoint(current ?: VisEditing.freeze(diagram, layout), id, point, line.points))
        return true
    }

    /** Takes in the layout [read] from the companion file, which is null if there is no such file. */
    fun loaded(read: VisLayout?) {
        if (isDragging) return
        val pending = unsaved
        if (pending != null) {
            if (read == pending) unsaved = null
            return
        }
        vis = read
    }

    private fun store(diagram: StatechartDiagram, layout: VisLayout) {
        val pruned = VisEditing.prune(diagram, layout)
        vis = pruned
        unsaved = pruned
        save(pruned)
    }
}
