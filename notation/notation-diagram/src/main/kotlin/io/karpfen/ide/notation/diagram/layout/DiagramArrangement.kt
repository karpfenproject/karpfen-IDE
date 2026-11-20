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

import io.karpfen.ide.core.text.TextRange

/**
 * How one kind of diagram is laid out, automatically and by hand. A view that lets the user arrange a diagram works
 * against this interface and so serves every kind of diagram: the statechart, the class diagram and the object
 * diagram each have an arrangement of their own.
 *
 * A diagram starts with the [automatic] layout. The first change by hand turns the layout on screen into a
 * [VisLayout] with [freeze]. From then on the diagram is drawn [byHand], and nothing is placed automatically any more
 * except elements that are new in the text. Every later change by hand first fixes those where they are drawn with
 * [completed].
 *
 * @param D the diagram, as it is derived from the text.
 * @param L the layout of that kind of diagram.
 */
public interface DiagramArrangement<D, L : DiagramLayout> {
    /** True if [diagram] has nothing to draw. */
    public fun isEmpty(diagram: D): Boolean

    /** The layout of [diagram] as the layout algorithm places it, with texts as wide as [text] says. */
    public fun automatic(diagram: D, text: TextMeasurer): L

    /** The layout of [diagram] as [vis] arranges it, with texts as wide as [text] says. */
    public fun byHand(diagram: D, vis: VisLayout, text: TextMeasurer): L

    /** The [layout] of [diagram] as a [VisLayout] with every element in it, all on the grid. */
    public fun freeze(diagram: D, layout: L): VisLayout

    /**
     * [vis] with a stored place for every box that [layout], the layout by hand of [diagram] as it is drawn, places
     * but [vis] has no place for. Such a box stands for an element that is new in the text since [vis] was made. The
     * layout by hand puts it in a row beside the stored boxes, so it has no place of its own: it cannot be moved, and
     * it moves whenever a stored box that defines the row moves. Fixing it where it is drawn makes it an element like
     * any other. The boxes that get a place are those that [freeze] stores for [layout].
     *
     * The picture stays the same: [byHand] draws the result exactly as [layout]. Whatever of [vis] is not in effect
     * in [layout] and would come into effect once the new boxes have places is dropped, because a change by hand
     * must not change more than what the user changed. A [vis] that needs nothing is given back as it is.
     */
    public fun completed(diagram: D, vis: VisLayout, layout: L): VisLayout

    /** [vis] with the box [box] of [diagram] moved by [delta], together with everything that belongs to it. */
    public fun moveBox(diagram: D, vis: VisLayout, box: String, delta: DiagramPoint): VisLayout

    /**
     * [vis] with the box [box] of [diagram] sized to [frame], if that box can be sized. The layout draws the box at
     * least this large, and larger where what lies in it needs the room.
     */
    public fun resize(diagram: D, vis: VisLayout, box: String, frame: DiagramBox): VisLayout

    /**
     * [vis] with only what [diagram] still has. The text is the source of truth, so what the text no longer has is
     * dropped. This is what is written to the companion file.
     */
    public fun prune(diagram: D, vis: VisLayout): VisLayout

    /**
     * [vis] carried over from the diagram [before] a change of the text to the diagram [after] it. An element that
     * was only renamed in the text keeps its place, its size, its waypoints and the place of its label. The renaming
     * is found by comparing the two diagrams, so it does not matter whether the name was changed in the text or
     * through the diagram.
     */
    public fun followed(before: D, after: D, vis: VisLayout): VisLayout

    /** The ids of the lines of [diagram] that can be bent at waypoints. */
    public fun lines(diagram: D): Set<String>

    /** The ids of the lines of [diagram] that carry a label, which the user can move. */
    public fun labelled(diagram: D): Set<String>

    /** Where the text names the element that the box [box] of [diagram] stands for, or null if there is none. */
    public fun placeInText(diagram: D, box: String): TextRange?
}

/**
 * The [layout] of [diagram] as a [VisLayout] that draws the same picture by hand. It is [DiagramArrangement.freeze]
 * with the labels kept in place: a label that the layout by hand would draw somewhere else than [layout] does is
 * stored with the distance between the two. So the first change by hand moves nothing but what the user moved.
 */
public fun <D, L : DiagramLayout> DiagramArrangement<D, L>.frozen(diagram: D, layout: L, text: TextMeasurer): VisLayout {
    val vis = freeze(diagram, layout)
    val usual = byHand(diagram, vis, text).labels
    val kept = layout.labels.mapNotNull { (id, drawn) ->
        val there = usual[id] ?: return@mapNotNull null
        val offset = DiagramGrid.snap(DiagramPoint(drawn.center.x - there.center.x, drawn.center.y - there.center.y))
        if (offset.x == 0f && offset.y == 0f) null else id to offset
    }
    return vis.copy(labels = kept.toMap())
}
