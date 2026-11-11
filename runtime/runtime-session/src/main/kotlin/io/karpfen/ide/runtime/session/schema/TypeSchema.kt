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
package io.karpfen.ide.runtime.session.schema

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.meta.SimplePropertyType

/**
 * A simple property of a type. It is a number, string or boolean, either atomic or a list.
 *
 * @property key the property's key.
 * @property type its simple type.
 * @property isList true for a list property.
 */
public data class PropertyShape(public val key: String, public val type: SimplePropertyType, public val isList: Boolean)

/**
 * A relation of a type to objects of another type.
 *
 * @property key the relation's key.
 * @property association `has` or `knows`. With `has` the target is embedded and belongs to the owner. With `knows` the
 *   target is a reference.
 * @property isList true for a list relation.
 * @property targetType the name of the type of its targets, as the metamodel writes it.
 */
public data class RelationShape(
    public val key: String,
    public val association: AssociationType,
    public val isList: Boolean,
    public val targetType: String,
)

/**
 * A type of a metamodel with its members in declaration order.
 *
 * @property name the type's name.
 * @property comment the type's description, or null if it has none.
 * @property properties its simple properties.
 * @property relations its relations.
 */
public data class TypeShape(
    public val name: String,
    public val comment: String?,
    public val properties: List<PropertyShape>,
    public val relations: List<RelationShape>,
) {
    /** The simple property [key], or null if the type has none of that key. */
    public fun property(key: String): PropertyShape? = properties.firstOrNull { it.key == key }

    /** The relation [key], or null if the type has none of that key. */
    public fun relation(key: String): RelationShape? = relations.firstOrNull { it.key == key }
}

/**
 * An immutable view of a dsl-tools metamodel. It is built during the preparation of a deployment, so that nothing
 * launched later holds a dsl-tools object. The mirror reads the domain metamodel through it. The event catalog reads
 * the events metamodel through it, whose types are the events file's own types followed by every domain type.
 *
 * A name that several types share resolves to the first of them, as the runtime's `getTypeByName` resolves it.
 *
 * @param types the types in metamodel order.
 */
public class TypeSchema(types: List<TypeShape>) {
    /** The types in metamodel order. */
    private val ordered: List<TypeShape> = types.toList()

    /** The first type of each name. */
    private val byName: Map<String, TypeShape> = LinkedHashMap<String, TypeShape>().also { map ->
        ordered.forEach { map.putIfAbsent(it.name, it) }
    }

    /** The names of the types in metamodel order, each once. */
    public val names: List<String> = byName.keys.toList()

    /** Every type in metamodel order. */
    public val types: List<TypeShape>
        get() = ordered

    /** The type [name], or null if the metamodel has none of that name. */
    public fun type(name: String): TypeShape? = byName[name]

    /** Equal when both hold the same types in the same order. */
    override fun equals(other: Any?): Boolean = other is TypeSchema && other.ordered == ordered

    /** The hash of the types. */
    override fun hashCode(): Int = ordered.hashCode()

    /** The type names, for diagnostics. */
    override fun toString(): String = "TypeSchema(names=$names)"

    public companion object {
        /**
         * The schema of [metamodel], with every type and its properties and relations. Comments are trimmed, and blank
         * comments become null.
         */
        public fun of(metamodel: Metamodel): TypeSchema = TypeSchema(metamodel.types.map(::shapeOf))

        /** The shape of the dsl-tools type [type]. */
        private fun shapeOf(type: ClassType): TypeShape =
            TypeShape(
                name = type.name,
                comment = type.comment.trim().ifEmpty { null },
                properties = type.simpleProperties.map { PropertyShape(it.key, it.propertyType, it.isList) },
                relations = type.objectProperties.map {
                    RelationShape(it.key, it.associationType, it.isList, it.reference.classTypeName)
                },
            )
    }
}
