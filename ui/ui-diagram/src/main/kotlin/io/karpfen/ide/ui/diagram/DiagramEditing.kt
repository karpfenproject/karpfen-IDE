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
import io.karpfen.ide.notation.diagram.layout.DiagramArrangement
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.FrameGrip
import io.karpfen.ide.notation.diagram.layout.VisEditing
import io.karpfen.ide.notation.diagram.layout.VisLayout

/**
 * The layout by hand of one diagram while the user works on it. It holds the [vis], which is null as long as the
 * diagram is laid out automatically, and turns the gestures of the canvas into changes of it. What a change means for
 * the kind of diagram at hand is the business of the [arrangement].
 *
 * The first change by hand freezes the layout on screen, so every element keeps its place and from then on nothing is
 * laid out automatically any more, except elements that are new in the text. A drag moves a waypoint, the label of a
 * line, a side or corner of a box that can be sized, a bar or a box, whichever lies under the pointer, in this order. A secondary click offers to remove the waypoint under the pointer
 * or to add one to the line under it. The change is made when the user takes the offer in the context menu.
 *
 * After every finished change, [save] gets the layout to write. What the text no longer has is dropped from it first.
 * [loaded] takes in what was read from the companion file. It is ignored while the user drags and while a change of
 * the user is still on its way to the file, so a reload never undoes what the user just did.
 *
 * All points are in the units of the diagram. The object belongs to the UI thread.
 *
 * @param D the diagram, as it is derived from the text.
 * @param L the layout of that kind of diagram.
 */
internal class DiagramEditing<D, L : DiagramLayout>(
    private val arrangement: DiagramArrangement<D, L>,
    private val save: (VisLayout) -> Unit,
) {

    /** The layout by hand, or null while the diagram is laid out automatically. */
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
        data class Box(val id: String) : Target
        data class Bar(val id: String) : Target
        data class Waypoint(val id: String, val index: Int) : Target
        data class Label(val id: String) : Target
        data class Frame(val grip: FrameGrip, val box: DiagramBox) : Target
    }

    /**
     * Starts a drag at [point] of the [diagram] drawn as [layout]. Returns true if an element was grabbed,
     * and false if the pointer is on the empty canvas, which the caller then moves as a whole.
     */
    fun startDrag(diagram: D, layout: L, point: DiagramPoint): Boolean {
        val current = vis
        val target = current?.let { VisEditing.waypointAt(it, arrangement.lines(diagram), point) }?.let { Target.Waypoint(it.first, it.second) }
            ?: layout.labelAt(point)?.takeIf { it in arrangement.labelled(diagram) }?.let(Target::Label)
            ?: layout.frameAt(point, DiagramLayout.HIT_TOLERANCE)?.let { grip -> layout.boxOf(grip.id)?.let { Target.Frame(grip, it) } }
            ?: layout.barAt(point)?.let(Target::Bar)
            ?: layout.boxAt(point)?.let(Target::Box)
            ?: return false
        val base = current ?: arrangement.freeze(diagram, layout)
        drag = Drag(target, base, current, point)
        vis = base
        isDragging = true
        return true
    }

    /** Moves the dragged element so that the pointer is at [point]. */
    fun dragTo(diagram: D, point: DiagramPoint) {
        val current = drag ?: return
        vis = when (val target = current.target) {
            is Target.Box -> arrangement.moveBox(diagram, current.base, target.id, DiagramPoint(point.x - current.start.x, point.y - current.start.y))
            is Target.Bar -> VisEditing.moveBar(current.base, target.id, point)
            is Target.Waypoint -> VisEditing.moveWaypoint(current.base, target.id, target.index, point)
            is Target.Label -> VisEditing.moveLabel(current.base, target.id, DiagramPoint(point.x - current.start.x, point.y - current.start.y))
            is Target.Frame -> arrangement.resize(diagram, current.base, target.grip.id, target.grip.resized(target.box, point))
        }
    }

    /** Ends the drag. A drag that moved nothing leaves everything as it was, also the automatic layout. */
    fun endDrag(diagram: D) {
        val current = drag ?: return
        drag = null
        isDragging = false
        val result = vis
        if (result == null || result == current.base) vis = current.previous else store(diagram, result)
    }

    /**
     * Gives the drag up, as after a failure in the middle of it. The layout goes back to what it was before the drag
     * started, and nothing is saved.
     */
    fun cancelDrag() {
        val current = drag ?: return
        drag = null
        isDragging = false
        vis = current.previous
    }

    /**
     * What a secondary click at [point] of the [diagram] drawn as [layout] offers, or null if there is nothing to
     * offer there. On a waypoint it is to remove the waypoint. On a line that can be bent it is to add a waypoint
     * there. Nothing changes until the offer is taken with [DiagramOffer.take].
     */
    fun offerAt(diagram: D, layout: L, point: DiagramPoint): DiagramOffer? {
        val current = vis
        val waypoint = current?.let { VisEditing.waypointAt(it, arrangement.lines(diagram), point) }
        if (current != null && waypoint != null) {
            return DiagramOffer(REMOVE_WAYPOINT) { store(diagram, VisEditing.removeWaypoint(current, waypoint.first, waypoint.second)) }
        }
        val line = layout.lineAt(point, DiagramLayout.HIT_TOLERANCE) ?: return null
        return DiagramOffer(ADD_WAYPOINT) {
            store(diagram, VisEditing.addWaypoint(current ?: arrangement.freeze(diagram, layout), line.id, point, line.points))
        }
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

    private fun store(diagram: D, layout: VisLayout) {
        val pruned = arrangement.prune(diagram, layout)
        vis = pruned
        unsaved = pruned
        save(pruned)
    }

    companion object {
        /** The entry of the context menu that adds a waypoint to a line. */
        const val ADD_WAYPOINT: String = "Add waypoint"

        /** The entry of the context menu that removes a waypoint. */
        const val REMOVE_WAYPOINT: String = "Remove waypoint"
    }
}

/**
 * A change of the layout that the context menu of a diagram offers.
 *
 * @property label what the entry of the menu says.
 * @property take makes the change and saves it.
 */
internal class DiagramOffer(val label: String, val take: () -> Unit)
