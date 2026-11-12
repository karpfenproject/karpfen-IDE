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

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode

/**
 * The Eclipse Layout Kernel as the automatic layouts use it: it runs the layered algorithm on a graph and reads the
 * result back in the diagram's own terms.
 *
 * The kernel reports positions relative to the node that contains an element. The functions here add up the positions
 * of the containers, so callers get absolute positions only.
 */
internal object ElkKernel {

    @Volatile
    private var registered = false

    /** Lets the kernel place the graph below [root]. */
    fun layout(root: ElkNode) {
        registerAlgorithm()
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
    }

    /** The box of [node] in the graph below [root]. */
    fun boxOf(node: ElkNode, root: ElkNode): DiagramBox {
        val origin = originOf(node.parent, root)
        return DiagramBox(origin.x + node.x.toFloat(), origin.y + node.y.toFloat(), node.width.toFloat(), node.height.toFloat())
    }

    /** The path of [edge] in the graph below [root], corner by corner, or null if the kernel gave the edge no path. */
    fun pointsOf(edge: ElkEdge, root: ElkNode): List<DiagramPoint>? {
        val section = edge.sections.firstOrNull() ?: return null
        val origin = originOf(edge.containingNode, root)
        val points = ArrayList<DiagramPoint>()
        points += DiagramPoint(origin.x + section.startX.toFloat(), origin.y + section.startY.toFloat())
        section.bendPoints.forEach { points += DiagramPoint(origin.x + it.x.toFloat(), origin.y + it.y.toFloat()) }
        points += DiagramPoint(origin.x + section.endX.toFloat(), origin.y + section.endY.toFloat())
        return points
    }

    /** The box of the first label of [edge] in the graph below [root], or null for an edge without a label. */
    fun labelOf(edge: ElkEdge, root: ElkNode): DiagramBox? {
        val label = edge.labels.firstOrNull() ?: return null
        val origin = originOf(edge.containingNode, root)
        return DiagramBox(origin.x + label.x.toFloat(), origin.y + label.y.toFloat(), label.width.toFloat(), label.height.toFloat())
    }

    /** The absolute position of the top left corner of [node]. The [root] sits at the origin. */
    private fun originOf(node: ElkNode?, root: ElkNode): DiagramPoint {
        var x = 0f
        var y = 0f
        var current = node
        while (current != null && current !== root) {
            x += current.x.toFloat()
            y += current.y.toFloat()
            current = current.parent
        }
        return DiagramPoint(x, y)
    }

    /** Makes the layered algorithm known to the kernel, once per process. */
    private fun registerAlgorithm() {
        if (registered) return
        synchronized(this) {
            if (!registered) {
                LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
                registered = true
            }
        }
    }
}
