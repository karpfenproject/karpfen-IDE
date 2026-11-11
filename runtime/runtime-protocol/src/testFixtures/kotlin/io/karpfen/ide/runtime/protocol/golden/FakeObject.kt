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
package io.karpfen.ide.runtime.protocol.golden

/**
 * An object of a model on the runtime, as a test value. It mirrors the runtime's `DataObject` with its id, its type
 * name, the simple properties present on the instance and its relations, each in instance order. [RuntimeWireWriter]
 * writes it exactly as the runtime's `ModelJsonWriter.write` writes a `DataObject`. Values of this type are immutable.
 *
 * A relation target is either a [FakeTarget.Nested] or a [FakeTarget.Known] object. A nested object is one that the
 * relation holds itself, which is an embedded `has` child or an anonymous object. A known object is an object of the
 * model referred to by id, and the writer resolves it through the model's objects when it writes. The runtime writes
 * both as full nested objects. So a model with references can form a reference loop, which the writer refuses as the
 * runtime does.
 *
 * @property id the object's id, `__id__` in its JSON. It is empty for an anonymous object.
 * @property typeName the name of its type, `__type__` in its JSON.
 * @property properties the simple properties present on the instance, in instance order.
 * @property relations the relations present on the instance, in instance order.
 */
public data class FakeObject(
    public val id: String,
    public val typeName: String,
    public val properties: List<FakeProperty> = emptyList(),
    public val relations: List<FakeRelation> = emptyList(),
) {
    /** The property [key], or null if the object has none of that key. */
    public fun property(key: String): FakeProperty? = properties.firstOrNull { it.key == key }

    /** The relation [key], or null if the object has none of that key. */
    public fun relation(key: String): FakeRelation? = relations.firstOrNull { it.key == key }
}

/** A value of a simple property, of one of the three simple types of a metamodel. */
public sealed interface FakeScalar {
    /**
     * A number. The runtime keeps every number as a double.
     *
     * @property value the number.
     */
    public data class Number(public val value: Double) : FakeScalar

    /**
     * A string.
     *
     * @property value the text.
     */
    public data class Text(public val value: String) : FakeScalar

    /**
     * A boolean.
     *
     * @property value the truth value.
     */
    public data class Flag(public val value: Boolean) : FakeScalar
}

/** A simple property of a [FakeObject]. */
public sealed interface FakeProperty {
    /** The property's key. */
    public val key: String

    /**
     * An atomic property.
     *
     * @property value its value.
     */
    public data class One(override val key: String, public val value: FakeScalar) : FakeProperty

    /**
     * A list property.
     *
     * @property values its values in order.
     */
    public data class Many(override val key: String, public val values: List<FakeScalar>) : FakeProperty
}

/** How a relation holds its targets, as the metamodel declares it. */
public enum class FakeAssociation {
    /** Embedded. The target belongs to the object and is removed with it. */
    HAS,

    /** Referred to. The target is an object of the model in its own right. */
    KNOWS,
}

/** The target of a relation. */
public sealed interface FakeTarget {
    /**
     * An object that the relation holds itself, which is an embedded child or an anonymous object.
     *
     * @property value the object.
     */
    public data class Nested(public val value: FakeObject) : FakeTarget

    /**
     * An object of the model referred to by its id. The writer resolves it when it writes. For an id the model does not
     * have, it writes `null`, as the runtime writes an unresolved reference.
     *
     * @property id the id of the object.
     */
    public data class Known(public val id: String) : FakeTarget
}

/** A relation of a [FakeObject]. */
public sealed interface FakeRelation {
    /** The relation's key. */
    public val key: String

    /** How the relation holds its targets. */
    public val association: FakeAssociation

    /**
     * An atomic relation.
     *
     * @property target its target, or null for an empty relation, which the runtime writes as `null`.
     */
    public data class One(
        override val key: String,
        override val association: FakeAssociation,
        public val target: FakeTarget?,
    ) : FakeRelation

    /**
     * A list relation.
     *
     * @property targets its targets in order. A null entry is an unresolved entry, which the runtime writes as `null`.
     */
    public data class Many(
        override val key: String,
        override val association: FakeAssociation,
        public val targets: List<FakeTarget?>,
    ) : FakeRelation
}

/** The [FakeObject] with [id] and [typeName] whose properties and relations [build] adds, in the order it adds them. */
public fun fakeObject(id: String, typeName: String, build: FakeObjectBuilder.() -> Unit = {}): FakeObject =
    FakeObjectBuilder().apply(build).build(id, typeName)

/** Adds properties and relations to a [FakeObject] in instance order. It is used through [fakeObject]. */
public class FakeObjectBuilder {
    /** The properties added so far, in order. */
    private val properties = ArrayList<FakeProperty>()

    /** The relations added so far, in order. */
    private val relations = ArrayList<FakeRelation>()

    /** Adds the number property [key]. */
    public fun number(key: String, value: Double) {
        properties += FakeProperty.One(key, FakeScalar.Number(value))
    }

    /** Adds the string property [key]. */
    public fun text(key: String, value: String) {
        properties += FakeProperty.One(key, FakeScalar.Text(value))
    }

    /** Adds the boolean property [key]. */
    public fun flag(key: String, value: Boolean) {
        properties += FakeProperty.One(key, FakeScalar.Flag(value))
    }

    /** Adds the number list property [key]. */
    public fun numbers(key: String, vararg values: Double) {
        properties += FakeProperty.Many(key, values.map { FakeScalar.Number(it) })
    }

    /** Adds the string list property [key]. */
    public fun texts(key: String, vararg values: String) {
        properties += FakeProperty.Many(key, values.map { FakeScalar.Text(it) })
    }

    /** Adds the boolean list property [key]. */
    public fun flags(key: String, vararg values: Boolean) {
        properties += FakeProperty.Many(key, values.map { FakeScalar.Flag(it) })
    }

    /** Adds the atomic `has` relation [key] holding [target], or an empty one for null. */
    public fun has(key: String, target: FakeObject?) {
        relations += FakeRelation.One(key, FakeAssociation.HAS, target?.let { FakeTarget.Nested(it) })
    }

    /** Adds the `has` list relation [key] holding [targets]. A null entry is unresolved. */
    public fun hasMany(key: String, vararg targets: FakeObject?) {
        relations += FakeRelation.Many(key, FakeAssociation.HAS, targets.map { target -> target?.let { FakeTarget.Nested(it) } })
    }

    /**
     * Adds the atomic `knows` relation [key] referring to the object with the id [targetId], or an empty one for null.
     */
    public fun knows(key: String, targetId: String?) {
        relations += FakeRelation.One(key, FakeAssociation.KNOWS, targetId?.let { FakeTarget.Known(it) })
    }

    /** Adds the `knows` list relation [key] referring to the objects with the ids [targetIds]. */
    public fun knowsMany(key: String, vararg targetIds: String) {
        relations += FakeRelation.Many(key, FakeAssociation.KNOWS, targetIds.map { FakeTarget.Known(it) })
    }

    /** The object with what was added. */
    internal fun build(id: String, typeName: String): FakeObject = FakeObject(id, typeName, properties.toList(), relations.toList())
}
