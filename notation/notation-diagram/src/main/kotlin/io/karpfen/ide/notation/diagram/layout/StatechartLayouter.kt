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

import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram

/**
 * Tells how much room a text needs. The layout cannot know the font of the view that will draw the diagram, so the
 * view hands in a measurer. The diagram's units are those the measurer answers in.
 */
public fun interface TextMeasurer {
    /** The width of [text] on one line. */
    public fun widthOf(text: String): Float

    public companion object {
        /** A measurer that counts [unitsPerCharacter] for every character, for tests and for a first guess. */
        public fun estimating(unitsPerCharacter: Float = 7f): TextMeasurer = TextMeasurer { it.length * unitsPerCharacter }
    }
}

/**
 * Places the elements of a statechart automatically. Nested states are placed inside their parent, the transitions
 * run around the states, and labels get room of their own.
 */
public fun interface StatechartLayouter {
    /** The layout of [diagram], with texts as wide as [text] says. */
    public fun layout(diagram: StatechartDiagram, text: TextMeasurer): StatechartLayout
}

/**
 * The layouter the IDE uses. It hands the work to the Eclipse Layout Kernel, a library for layered graph layout. The
 * library stays behind this function, so another layouter can take its place.
 */
public fun StatechartLayouter(): StatechartLayouter = ElkStatechartLayouter()
