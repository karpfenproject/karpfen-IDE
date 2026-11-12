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

import io.karpfen.ide.notation.diagram.structure.StructureNode

/**
 * The measures of a drawn class or object diagram, in the diagram's own units. The automatic layout and the layout by
 * hand share them, so a box has the same size in both.
 */
public object StructureMetrics {
    /** The height of the top part of a box, which holds its title. */
    public const val TITLE_HEIGHT: Float = 30f

    /** The height of one row below the title. */
    public const val ROW_HEIGHT: Float = 18f

    /** The room above the first and below the last row. */
    public const val ROWS_PADDING: Float = 6f

    /** The room left and right of the title and the rows. */
    public const val TEXT_PADDING: Float = 12f

    /** The smallest width of a box. */
    public const val MIN_WIDTH: Float = 90f

    /** The room around the whole drawing. */
    public const val MARGIN: Float = 24f

    /** The height of the label of a link. */
    public const val LABEL_HEIGHT: Float = 16f

    /** The room a label gets beyond the width of its text. */
    public const val LABEL_PADDING: Float = 8f

    /** The distance between two boxes that are placed next to each other automatically. */
    public const val GAP: Float = 40f

    /** The width of the box of [node], with texts as wide as [text] says. */
    public fun width(node: StructureNode, text: TextMeasurer): Float =
        maxOf((node.rows + node.title).maxOf(text::widthOf) + 2 * TEXT_PADDING, MIN_WIDTH)

    /** The height of the box of [node]. A box without rows is as high as its title. */
    public fun height(node: StructureNode): Float =
        if (node.rows.isEmpty()) TITLE_HEIGHT else TITLE_HEIGHT + node.rows.size * ROW_HEIGHT + 2 * ROWS_PADDING
}
