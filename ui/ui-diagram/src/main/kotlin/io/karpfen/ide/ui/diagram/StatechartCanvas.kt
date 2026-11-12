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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Draws [diagram] and lets the user move and zoom the drawing. A click on a state calls [onSelect] with the place of
 * the state's name in the text.
 *
 * The texts are measured here, in the font of the view, and the layout is computed from these measures off the UI
 * thread. Until the first layout is there, the canvas is empty and the zoom buttons are not shown. When the diagram
 * changes, the last drawing stays until
 * the new layout is ready, so the view does not flicker while the user types.
 *
 * The first drawing is fitted into the view. After that the view keeps the zoom and the position the user chose.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.StatechartCanvas(diagram: StatechartDiagram, onSelect: (TextRange) -> Unit) {
    val colors = KarpfenTheme.colors
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val nameStyle = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold, color = colors.text)
    val labelStyle = KarpfenTheme.typography.uiSmall.copy(color = colors.text)
    val noteStyle = KarpfenTheme.typography.uiSmall.copy(color = colors.textMuted)
    val texts = remember(diagram, nameStyle, labelStyle, noteStyle, density) {
        StatechartTexts.measure(diagram, measurer, nameStyle, labelStyle, noteStyle)
    }
    val drawing by produceState<StatechartDrawing?>(null, diagram, texts) {
        val layout = withContext(Dispatchers.Default) {
            StatechartLayouter().layout(diagram, TextMeasurer { texts.widthOf(it) / density })
        }
        value = StatechartDrawing(diagram, layout, texts)
    }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var fitted by remember { mutableStateOf(false) }

    fun fit(current: StatechartDrawing) {
        val width = current.layout.width * density
        val height = current.layout.height * density
        if (width <= 0f || height <= 0f || viewSize == IntSize.Zero) return
        zoom = minOf(1f, viewSize.width / width, viewSize.height / height).coerceAtLeast(MIN_ZOOM)
        pan = Offset((viewSize.width - width * zoom) / 2f, (viewSize.height - height * zoom) / 2f)
    }

    fun zoomAt(pivot: Offset, factor: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        pan = pivot - (pivot - pan) * (next / zoom)
        zoom = next
    }

    LaunchedEffect(drawing, viewSize) {
        val current = drawing
        if (!fitted && current != null && viewSize != IntSize.Zero) {
            fit(current)
            fitted = true
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    pan += drag
                }
            }
            .pointerInput(drawing) {
                detectTapGestures { position ->
                    val current = drawing ?: return@detectTapGestures
                    val point = (position - pan) / (zoom * density)
                    current.layout.stateAt(DiagramPoint(point.x, point.y))
                        ?.let(current.diagram::state)
                        ?.let { onSelect(it.nameRange) }
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val delta = change.scrollDelta
                if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
                    zoomAt(change.position, if (delta.y < 0f) ZOOM_STEP else 1f / ZOOM_STEP)
                } else {
                    pan -= delta * SCROLL_DISTANCE
                }
                change.consume()
            }
            .testTag(StatechartViewTestTags.CANVAS),
    ) {
        drawing?.let { drawStatechart(it, colors, pan, zoom) }
    }

    if (drawing == null) return
    Row(
        Modifier.align(Alignment.TopEnd).padding(8.dp).background(colors.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
        CanvasButton("-", StatechartViewTestTags.ZOOM_OUT) { zoomAt(center, 1f / ZOOM_STEP) }
        CanvasButton("${(zoom * PERCENT).roundToInt()}%", StatechartViewTestTags.ZOOM) { zoomAt(center, 1f / zoom) }
        CanvasButton("+", StatechartViewTestTags.ZOOM_IN) { zoomAt(center, ZOOM_STEP) }
        CanvasButton("Fit", StatechartViewTestTags.FIT) { drawing?.let(::fit) }
    }
}

/** A small button over the canvas. It never takes the keyboard focus, so the editor next to the diagram keeps it. */
@Composable
private fun CanvasButton(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = KarpfenTheme.colors.text,
        style = KarpfenTheme.typography.uiSmall,
        modifier = Modifier
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag),
    )
}

/** The smallest zoom of the drawing. */
private const val MIN_ZOOM = 0.2f

/** The largest zoom of the drawing. */
private const val MAX_ZOOM = 3f

/** The factor of one zoom step. */
private const val ZOOM_STEP = 1.1f

/** How far one notch of the scroll wheel moves the drawing, in pixels. */
private const val SCROLL_DISTANCE = 40f

/** The percent in one whole. */
private const val PERCENT = 100
