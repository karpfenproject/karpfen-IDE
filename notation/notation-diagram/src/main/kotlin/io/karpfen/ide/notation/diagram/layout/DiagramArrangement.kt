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
 * except elements that are new in the text.
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

    /** [vis] with the box [box] of [diagram] moved by [delta], together with everything that belongs to it. */
    public fun moveBox(diagram: D, vis: VisLayout, box: String, delta: DiagramPoint): VisLayout

    /**
     * [vis] with only what [diagram] still has. The text is the source of truth, so what the text no longer has is
     * dropped. This is what is written to the companion file.
     */
    public fun prune(diagram: D, vis: VisLayout): VisLayout

    /** The ids of the lines of [diagram] that can be bent at waypoints. */
    public fun lines(diagram: D): Set<String>

    /** Where the text names the element that the box [box] of [diagram] stands for, or null if there is none. */
    public fun placeInText(diagram: D, box: String): TextRange?
}
