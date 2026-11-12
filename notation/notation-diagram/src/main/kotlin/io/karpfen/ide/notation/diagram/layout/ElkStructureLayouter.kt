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

import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * A [StructureLayouter] on the layered algorithm of the Eclipse Layout Kernel. It builds the kernel's graph from the
 * diagram, lets the kernel place it, and reads the positions back.
 *
 * A box becomes a node and a link an edge from its source to its target, with a label as large as the text of the
 * link. The drawing reads from the top down, so what owns something stands above it. Boxes of one layer keep the
 * order of the text where the kernel has a choice.
 *
 * A link that names a box the diagram does not have is left out of the layout.
 */
internal class ElkStructureLayouter : StructureLayouter {

    override fun layout(diagram: StructureDiagram, text: TextMeasurer): StructureLayout {
        if (diagram.nodes.isEmpty()) return StructureLayout.EMPTY
        val root = ElkGraphUtil.createGraph().apply {
            setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
            setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
            setProperty(CoreOptions.PADDING, ElkPadding(StructureMetrics.MARGIN.toDouble()))
            setProperty(CoreOptions.SPACING_NODE_NODE, NODE_SPACING)
            setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
            setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, LAYER_SPACING)
            setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, EDGE_SPACING)
            setProperty(LayeredOptions.SPACING_EDGE_EDGE, EDGE_SPACING)
        }
        val nodes = LinkedHashMap<String, ElkNode>()
        for (node in diagram.nodes) {
            if (node.id in nodes) continue
            nodes[node.id] = ElkGraphUtil.createNode(root).apply {
                width = StructureMetrics.width(node, text).toDouble()
                height = StructureMetrics.height(node).toDouble()
            }
        }
        val edges = LinkedHashMap<String, ElkEdge>()
        for (link in diagram.links) {
            val source = nodes[link.source] ?: continue
            val target = nodes[link.target] ?: continue
            val edge = ElkGraphUtil.createSimpleEdge(source, target)
            if (link.label.isNotEmpty()) {
                ElkGraphUtil.createLabel(link.label, edge).apply {
                    width = (text.widthOf(link.label) + StructureMetrics.LABEL_PADDING).toDouble()
                    height = StructureMetrics.LABEL_HEIGHT.toDouble()
                }
            }
            edges[link.id] = edge
        }
        ElkKernel.layout(root)
        return StructureLayout(
            boxes = nodes.mapValues { ElkKernel.boxOf(it.value, root) },
            routes = edges.mapNotNull { (id, edge) -> ElkKernel.pointsOf(edge, root)?.let { LinkRoute(id, it, ElkKernel.labelOf(edge, root)) } },
        )
    }

    private companion object {
        /** The distance between two boxes of one layer. */
        const val NODE_SPACING = 40.0

        /** The distance between two layers of boxes. */
        const val LAYER_SPACING = 56.0

        /** The distance between two lines, and between a line and a box of the next layer. */
        const val EDGE_SPACING = 18.0
    }
}
