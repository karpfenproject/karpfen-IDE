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
package io.karpfen.ide.notation.diagram.structure

import io.karpfen.ide.core.text.TextRange

/** What the boxes of a [StructureDiagram] stand for. */
public enum class StructureKind {
    /** The types of a metamodel. The diagram is a class diagram. */
    TYPES,

    /** The objects of a model. The diagram is an object diagram. */
    OBJECTS,
}

/**
 * A diagram of boxes and the links between them. It serves the two diagrams of the structure of a model: the class
 * diagram of a `.kmeta` file, whose boxes are types, and the object diagram of a `.kmodel` file, whose boxes are
 * objects. Both look alike: a box has a title and rows of text below it, and a link is a containment or a reference.
 *
 * The diagram is derived from the text by [MetamodelProjection] or [ModelProjection] and holds no positions. Where
 * the elements are drawn is the business of the layout.
 *
 * @property kind what the boxes stand for.
 * @property nodes the boxes in the order of the text.
 * @property links the links in the order of the text.
 */
public data class StructureDiagram(
    public val kind: StructureKind,
    public val nodes: List<StructureNode>,
    public val links: List<StructureLink>,
) {
    /** The box with the id [id], or null. */
    public fun node(id: String): StructureNode? = nodes.firstOrNull { it.id == id }
}

/**
 * A box of a [StructureDiagram].
 *
 * @property id identifies the box within its diagram. It is the name of the type or the key of the object. Where the
 *   text declares a name more than once, the later ones get their order appended, as in `Bulb#2`. A layout file
 *   refers to a box by it.
 * @property title what the box shows at its top: the name of a type, or the key and the type of an object, as in
 *   `bulb : Bulb`.
 * @property rows the lines below the title. A type shows its simple properties, as in `watts: number`. An object
 *   shows the values of its properties, as in `watts = 40`.
 * @property range the whole declaration in the text.
 * @property nameRange the name inside its quotes.
 */
public data class StructureNode(
    public val id: String,
    public val title: String,
    public val rows: List<String>,
    public val range: TextRange,
    public val nameRange: TextRange,
)

/** The two kinds of link between boxes. */
public enum class LinkKind {
    /** `has`: the source owns the target. */
    CONTAINMENT,

    /** `knows`: the source refers to the target without owning it. */
    REFERENCE,
}

/**
 * A link of a [StructureDiagram].
 *
 * @property id identifies the link within its diagram. It is made of the word of the relation, the source and the
 *   name of the relation, as in `has:Lamp.bulb`. A layout file refers to a link by it.
 * @property kind containment or reference.
 * @property source the id of the box the link starts at, which is the one that declares the relation.
 * @property target the id of the box the link leads to.
 * @property label the name of the relation. A relation to a list of a type carries the mark `[*]` after its name.
 * @property range the whole relation in the text.
 */
public data class StructureLink(
    public val id: String,
    public val kind: LinkKind,
    public val source: String,
    public val target: String,
    public val label: String,
    public val range: TextRange,
)
