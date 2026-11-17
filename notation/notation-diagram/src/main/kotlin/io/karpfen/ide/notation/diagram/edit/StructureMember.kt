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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.structure.StructureDiagram

/**
 * A statement of a type or an object as the class diagram or the object diagram shows it: as a row of its box, or as
 * a link that leaves the box. A relation is a row where its target cannot be drawn and a link where it can, and the
 * edits of a relation work on both.
 */
public sealed interface StructureMember {

    /** The range of the statement in the text that [diagram] was derived from, or null if [diagram] has no such member. */
    public fun rangeIn(diagram: StructureDiagram): TextRange?

    /**
     * The row [index] of the box [box], counted from the first row below the title.
     *
     * @property box the id of the box.
     * @property index the index of the row among the rows of the box.
     */
    public data class Row(public val box: String, public val index: Int) : StructureMember {
        override fun rangeIn(diagram: StructureDiagram): TextRange? = diagram.node(box)?.rowRanges?.getOrNull(index)
    }

    /**
     * The link [id].
     *
     * @property id the id of the link, as in `has:Lamp.bulb`.
     */
    public data class Link(public val id: String) : StructureMember {
        override fun rangeIn(diagram: StructureDiagram): TextRange? = diagram.links.firstOrNull { it.id == id }?.range
    }
}
