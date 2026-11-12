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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.layout.DiagramArrangement
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.layout.VisEditing
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.foundation.widgets.ContextMenuAction
import io.karpfen.ide.ui.foundation.widgets.ContextMenuPopup
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws [diagram], a diagram of the kind [notation], and lets the user move and zoom the drawing and arrange it by
 * hand through [editing]. A click on a box calls [onSelect] with the place of its name in the text.
 *
 * As long as [editing] holds no layout by hand, the layout is computed automatically, off the UI thread, from the
 * texts measured here in the font of the view. Until the first layout is there, the canvas is empty and the zoom
 * buttons are not shown. Once there is a layout by hand, the drawing follows it at once, so a dragged element moves
 * with the pointer.
 *
 * A drag that starts on a box, a bar, a waypoint or the label of a line moves that element on the grid, and the grid
 * is shown while it moves. A drag that starts on the border of a box that can be sized moves that side or corner. The
 * border is drawn in the accent colour while the pointer is on it. The waypoints of a line are only drawn while the
 * pointer is near the line. A drag that starts on the empty canvas moves the whole drawing. A secondary click on a line that can be bent
 * or on a waypoint opens a small context menu at the pointer, with one entry that adds a waypoint there or removes
 * the one under the pointer. Nothing changes until the user clicks the entry. A click elsewhere closes the menu, and
 * so does a change of the drawing.
 *
 * The first drawing is fitted into the view. After that the view keeps the zoom and the position the user chose.
 *
 * Nothing a notation does here can take the window down. Measuring, laying out, drawing and every gesture run
 * through [faults]. If the layout fails, the elements are placed in a plain row. If that fails as well, the canvas
 * stays empty. What could not be done is left out, and [faults] holds a note about it for the view to show.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <D : Any, L : DiagramLayout> BoxScope.DiagramCanvas(
    notation: DiagramNotation<D, L>,
    diagram: D,
    editing: DiagramEditing<D, L>,
    faults: DiagramFaults,
    onSelect: (TextRange) -> Unit,
) {
    val colors = KarpfenTheme.colors
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val styles = DiagramTextStyles(
        title = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold, color = colors.text),
        row = KarpfenTheme.typography.uiSmall.copy(color = colors.text),
        label = KarpfenTheme.typography.uiSmall.copy(color = colors.text),
    )
    val arrangement = notation.arrangement
    val texts = remember(notation, diagram, styles, density) {
        faults.guard("measuring the texts", { DiagramTexts(emptyMap()) }) {
            DiagramTexts.measure(notation.painter.texts(diagram), measurer, styles)
        }
    }
    val widths = remember(texts, density) { TextMeasurer { texts.widthOf(it) / density } }
    val vis = editing.vis
    val automatic by produceState<DiagramDrawing<D, L>?>(null, notation, diagram, texts, vis == null) {
        if (vis == null) {
            val layout = faults.guard<L?>("laying the diagram out", { null }) {
                withContext(Dispatchers.Default) { arrangement.automatic(diagram, widths) }
            } ?: inARow(arrangement, diagram, widths, faults)
            value = layout?.let { DiagramDrawing(diagram, it, texts) }
        }
    }
    val drawing = if (vis != null) {
        remember(notation, diagram, texts, vis) {
            val layout = faults.guard<L?>("arranging the diagram as its layout file says", { null }) {
                arrangement.byHand(diagram, vis, widths)
            } ?: inARow(arrangement, diagram, widths, faults)
            layout?.let { DiagramDrawing(diagram, it, texts) }
        }
    } else {
        automatic
    }
    val current by rememberUpdatedState(drawing)
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var fitted by remember { mutableStateOf(false) }
    var menu by remember(drawing) { mutableStateOf<MenuRequest?>(null) }
    var pointer by remember { mutableStateOf<DiagramPoint?>(null) }

    fun pointAt(position: Offset): DiagramPoint {
        val point = (position - pan) / (zoom * density)
        return DiagramPoint(point.x, point.y)
    }

    fun fit(shown: DiagramDrawing<D, L>) {
        val bounds = faults.guard("fitting the diagram into the view", { DiagramBox(0f, 0f, 0f, 0f) }) { shown.layout.bounds }
        val width = bounds.width * density
        val height = bounds.height * density
        if (width <= 0f || height <= 0f || viewSize == IntSize.Zero) return
        val margin = FIT_MARGIN * density
        zoom = minOf(1f, (viewSize.width - 2 * margin) / width, (viewSize.height - 2 * margin) / height).coerceAtLeast(MIN_ZOOM)
        pan = Offset(
            (viewSize.width - width * zoom) / 2f - bounds.x * density * zoom,
            (viewSize.height - height * zoom) / 2f - bounds.y * density * zoom,
        )
    }

    fun zoomAt(pivot: Offset, factor: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        pan = pivot - (pivot - pan) * (next / zoom)
        zoom = next
    }

    LaunchedEffect(drawing != null, viewSize) {
        val shown = drawing
        if (!fitted && shown != null && viewSize != IntSize.Zero) {
            fit(shown)
            fitted = true
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                var grabbed = false
                detectDragGestures(
                    onDragStart = { position ->
                        val shown = current
                        grabbed = shown != null && faults.guard("taking hold of an element", { false }) {
                            editing.startDrag(shown.diagram, shown.layout, pointAt(position))
                        }
                    },
                    onDragEnd = { current?.let { shown -> faults.guard("dropping an element", editing::cancelDrag) { editing.endDrag(shown.diagram) } } },
                    onDragCancel = { current?.let { shown -> faults.guard("dropping an element", editing::cancelDrag) { editing.endDrag(shown.diagram) } } },
                ) { change, drag ->
                    change.consume()
                    val shown = current
                    if (grabbed && shown != null) {
                        faults.guard("moving an element", editing::cancelDrag) { editing.dragTo(shown.diagram, pointAt(change.position)) }
                    } else {
                        pan += drag
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    val shown = current ?: return@detectTapGestures
                    faults.attempt("finding the clicked element in the text") {
                        shown.layout.boxAt(pointAt(position))?.let { arrangement.placeInText(shown.diagram, it) }?.let(onSelect)
                    }
                }
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                val shown = current ?: return@onPointerEvent
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (!event.buttons.isSecondaryPressed) return@onPointerEvent
                val offer = faults.guard<DiagramOffer?>("opening the context menu", { null }) {
                    editing.offerAt(shown.diagram, shown.layout, pointAt(change.position))
                } ?: return@onPointerEvent
                menu = MenuRequest(change.position, offer)
                change.consume()
            }
            .onPointerEvent(PointerEventType.Move) { event -> pointer = event.changes.firstOrNull()?.let { pointAt(it.position) } }
            .onPointerEvent(PointerEventType.Exit) { pointer = null }
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
            .testTag(DiagramViewTestTags.CANVAS),
    ) {
        val shown = drawing ?: return@Canvas
        if (editing.isDragging) drawGrid(colors, pan, zoom)
        withTransform({
            translate(pan.x, pan.y)
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            faults.attempt("drawing the diagram") {
                notation.painter.paint(this, shown.diagram, shown.layout, shown.texts, colors, faults)
            }
            val at = pointer
            if (at != null) {
                faults.attempt("drawing what the pointer has hold of") {
                    if (vis != null) {
                        val near = shown.layout.lineAt(at, WAYPOINT_REACH)?.id ?: VisEditing.waypointAt(vis, arrangement.lines(shown.diagram), at)?.first
                        near?.let { id -> drawWaypoints(vis.waypoints[id].orEmpty(), colors) }
                    }
                    shown.layout.frameAt(at, DiagramLayout.HIT_TOLERANCE)?.let { grip -> shown.layout.boxOf(grip.id) }?.let { drawFrameGrip(it, colors) }
                }
            }
        }
    }

    menu?.let { request ->
        Box(Modifier.offset { IntOffset(request.position.x.roundToInt(), request.position.y.roundToInt()) }.size(0.dp)) {
            val take = { faults.attempt("changing a waypoint") { request.offer.take() } }
            ContextMenuPopup(listOf(ContextMenuAction(request.offer.label, onClick = take)), DpOffset.Zero) { menu = null }
        }
    }

    if (drawing == null) return
    Row(
        Modifier.align(Alignment.TopEnd).padding(8.dp).background(colors.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
        CanvasButton("-", DiagramViewTestTags.ZOOM_OUT) { zoomAt(center, 1f / ZOOM_STEP) }
        CanvasButton("${(zoom * PERCENT).roundToInt()}%", DiagramViewTestTags.ZOOM) { zoomAt(center, 1f / zoom) }
        CanvasButton("+", DiagramViewTestTags.ZOOM_IN) { zoomAt(center, ZOOM_STEP) }
        CanvasButton("Fit", DiagramViewTestTags.FIT) { current?.let(::fit) }
    }
}

/**
 * The plainest layout of [diagram] there is: every element in a row, as [arrangement] places elements that are new in
 * the text. It is what the canvas falls back to when the proper layout fails. Null if this fails too.
 */
private fun <D, L : DiagramLayout> inARow(arrangement: DiagramArrangement<D, L>, diagram: D, widths: TextMeasurer, faults: DiagramFaults): L? =
    faults.guard<L?>("placing the elements in a row", { null }) { arrangement.byHand(diagram, VisLayout(), widths) }

/** The context menu to show: the [offer] it holds and the [position] of the pointer on the canvas, in pixels. */
private class MenuRequest(val position: Offset, val offer: DiagramOffer)

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

/** How near to a line the pointer must be for the waypoints of the line to show, in the units of the diagram. */
private const val WAYPOINT_REACH = 14f

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

/** The room that fitting leaves around the drawing, in density-independent units. */
private const val FIT_MARGIN = 16f
