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

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.RouteKind
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.ui.foundation.theme.KarpfenColors

/**
 * Draws a statechart.
 *
 * States are drawn parents first, so nested states lie on top of their parent. A state with nested states shows its
 * name in a row at its top. A state without shows it in its middle, with a small line below if it has an entry or a
 * do block. The marker of an initial state is a filled dot. The bars of splits and joins are filled. Every line ends
 * in an arrowhead, except the lines that lead into a bar. The caption of a transition is written on a patch in the
 * colour of the background.
 */
internal object StatechartPainter : DiagramPainter<StatechartDiagram, StatechartLayout> {

    /** The radius of the corners of a state. */
    private const val CORNER = 8f

    /** The room left of the name in the top row of a state with nested states. */
    private const val NAME_INSET = 10f

    override fun texts(diagram: StatechartDiagram): Map<TextRole, Collection<String>> {
        val states = diagram.allStates()
        return mapOf(
            TextRole.TITLE to states.map { it.name },
            TextRole.ROW to states.mapNotNull(::noteOf),
            TextRole.LABEL to diagram.transitions.map { it.caption },
        )
    }

    override fun paint(scope: DrawScope, diagram: StatechartDiagram, layout: StatechartLayout, texts: DiagramTexts, colors: KarpfenColors) {
        val unit = scope.density
        for (state in diagram.allStates()) {
            val box = layout.states[state.name] ?: continue
            scope.drawState(state, box, layout, texts, colors)
        }
        layout.initialMarkers.values.forEach { marker ->
            scope.drawCircle(colors.text, radius = marker.width * unit / 2f, center = marker.center(unit))
        }
        layout.bars.values.forEach { bar -> scope.drawRect(colors.text, bar.topLeft(unit), bar.size(unit)) }
        layout.routes.forEach { route ->
            val pixels = scope.drawPolyline(route.points, colors.textMuted) ?: return@forEach
            if (route.kind != RouteKind.INTO_BAR) scope.drawFilledArrowhead(pixels, colors.textMuted)
        }
        val captions = diagram.transitions.associate { it.id to it.caption }
        layout.routes.forEach { route ->
            val label = route.label ?: return@forEach
            val caption = captions[route.transitionId]?.let { texts[TextRole.LABEL, it] } ?: return@forEach
            scope.drawCaption(label, caption, colors)
        }
    }

    private fun DrawScope.drawState(state: StateNode, box: DiagramBox, layout: StatechartLayout, texts: DiagramTexts, colors: KarpfenColors) {
        val unit = density
        val corner = CornerRadius(CORNER * unit)
        val topLeft = box.topLeft(unit)
        val size = box.size(unit)
        drawRoundRect(if (state.isComposite) colors.window else colors.panel, topLeft, size, corner)
        drawRoundRect(colors.border, topLeft, size, corner, style = Stroke(BORDER * unit))
        val name = texts[TextRole.TITLE, state.name] ?: return
        if (state.isComposite) {
            val header = layout.headerHeight * unit
            drawText(name, topLeft = topLeft + Offset(NAME_INSET * unit, (header - name.size.height) / 2f))
            drawLine(colors.border, topLeft + Offset(0f, header), topLeft + Offset(size.width, header), strokeWidth = BORDER * unit)
            return
        }
        val note = noteOf(state)?.let { texts[TextRole.ROW, it] }
        val textHeight = name.size.height + (note?.size?.height ?: 0)
        val top = topLeft.y + (size.height - textHeight) / 2f
        drawText(name, topLeft = Offset(topLeft.x + (size.width - name.size.width) / 2f, top))
        note?.let {
            drawText(it, color = colors.textMuted, topLeft = Offset(topLeft.x + (size.width - it.size.width) / 2f, top + name.size.height))
        }
    }

    /** What the line below the name of [state] says, or null for a state without blocks. */
    private fun noteOf(state: StateNode): String? = when {
        state.hasEntry && state.hasDo -> "entry, do"
        state.hasEntry -> "entry"
        state.hasDo -> "do"
        else -> null
    }
}
