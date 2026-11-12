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

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.ui.foundation.theme.KarpfenColors

/**
 * Draws one kind of diagram. The canvas handles what all diagrams share: moving, zooming, the grid and the handles of
 * waypoints. A painter adds what its kind of diagram looks like.
 *
 * @param D the diagram, as it is derived from the text.
 * @param L the layout of that kind of diagram.
 */
internal interface DiagramPainter<D, L : DiagramLayout> {

    /** Every text that the drawing of [diagram] writes, by its role. The canvas measures them before the layout. */
    fun texts(diagram: D): Map<TextRole, Collection<String>>

    /**
     * Draws [diagram] as [layout] places it onto [scope], with the measured [texts]. The canvas has already moved and
     * scaled [scope]. The layout is in density-independent units, so every length is multiplied by the density.
     *
     * Every element is drawn through [faults], each on its own. So an element that cannot be drawn is left out and
     * the others still appear.
     */
    fun paint(scope: DrawScope, diagram: D, layout: L, texts: DiagramTexts, colors: KarpfenColors, faults: DiagramFaults)
}
