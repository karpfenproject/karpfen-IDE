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
package io.karpfen.ide.runtime.session.mirror

import io.karpfen.dsl.meta.AssociationType

/** Where an object of the mirror comes from. */
public enum class ObjectOrigin {
    /** The object is declared in the deployed model. */
    MODEL,

    /** The runtime created the object while the environment ran. The deployed model does not declare its id. */
    RUNTIME,
}

/** A value of a simple property, of one of the three simple types. The runtime keeps every number as a double. */
public sealed interface ScalarValue {
    /**
     * A number.
     *
     * @property value the number.
     */
    public data class Number(public val value: Double) : ScalarValue

    /**
     * A string.
     *
     * @property value the text.
     */
    public data class Text(public val value: String) : ScalarValue

    /**
     * A boolean.
     *
     * @property value the truth value.
     */
    public data class Flag(public val value: Boolean) : ScalarValue
}

/** The value of a simple property. */
public sealed interface PropertyValue {
    /**
     * The value of an atomic property.
     *
     * @property value the value.
     */
    public data class Single(public val value: ScalarValue) : PropertyValue

    /**
     * The values of a list property.
     *
     * @property values the values in order.
     */
    public data class Many(public val values: List<ScalarValue>) : PropertyValue
}

/**
 * A simple property present on an object.
 *
 * @property key the property's key.
 * @property value its value.
 */
public data class MirrorProperty(public val key: String, public val value: PropertyValue)

/** The target of a relation in the mirror. */
public sealed interface ObjectRef {
    /**
     * An object with an id, held in [MirrorSnapshot.objects].
     *
     * @property id the object's id.
     */
    public data class Keyed(public val id: String) : ObjectRef

    /**
     * An object without an id, which only its relation holds. It lives inline.
     *
     * @property value the object, whose id is empty.
     */
    public data class Anonymous(public val value: MirrorObject) : ObjectRef

    /**
     * An entry of a list relation that refers to no object. It stands for an unresolved entry of the model and for the
     * runtime's `null`.
     */
    public data object Missing : ObjectRef
}

/**
 * A relation present on an object.
 *
 * @property key the relation's key.
 * @property association `has` or `knows`. It is null when the schema does not know the object's type, or when the
 *   relation is not a relation of its type.
 * @property isList true for a list relation. An atomic relation holds at most one target, and an empty one holds
 *   none.
 * @property targets the targets in order.
 */
public data class MirrorRelation(
    public val key: String,
    public val association: AssociationType?,
    public val isList: Boolean,
    public val targets: List<ObjectRef>,
)

/**
 * An object of the mirror. It holds its type, the properties and relations present on it in instance order, where it
 * comes from and the revision that last wrote it.
 *
 * @property id the object's id. It is empty for an anonymous object.
 * @property typeName the name of its type.
 * @property properties the simple properties present on it, in instance order.
 * @property relations the relations present on it, in instance order.
 * @property origin whether the deployed model declares it.
 * @property revision the revision of the snapshot or push that last wrote it. It is 0 for the seed.
 */
public data class MirrorObject(
    public val id: String,
    public val typeName: String,
    public val properties: List<MirrorProperty>,
    public val relations: List<MirrorRelation>,
    public val origin: ObjectOrigin,
    public val revision: Long,
) {
    /** The property [key], or null if the object has none of that key. */
    public fun property(key: String): MirrorProperty? = properties.firstOrNull { it.key == key }

    /** The relation [key], or null if the object has none of that key. */
    public fun relation(key: String): MirrorRelation? = relations.firstOrNull { it.key == key }
}

/**
 * Where a keyed object is embedded. The owner is the nearest keyed object that holds it through `has` relations only.
 * The relation is the owner's relation through which it is held.
 *
 * @property owner the id of the owner.
 * @property relation the key of the owner's relation.
 */
public data class Containment(public val owner: String, public val relation: String)

/**
 * The model as values. It holds every keyed object by id, the roots of the model, and where each embedded keyed object
 * is held. Immutable.
 *
 * @property objects every keyed object by id, in the order the model or the runtime first showed them.
 * @property roots the root objects of the model in model order.
 * @property containment for every keyed object held through `has` relations, its owner and the owner's relation.
 */
public data class MirrorSnapshot(
    public val objects: Map<String, MirrorObject>,
    public val roots: List<ObjectRef>,
    public val containment: Map<String, Containment>,
) {
    public companion object {
        /** The mirror without objects. */
        public val EMPTY: MirrorSnapshot = MirrorSnapshot(emptyMap(), emptyList(), emptyMap())
    }
}

/**
 * What one step changed in the mirror, by object id.
 *
 * @property changed objects that existed before and whose type, properties or relations differ now.
 * @property created objects that did not exist before.
 * @property deleted objects that no longer exist.
 */
public data class MirrorChange(public val changed: Set<String>, public val created: Set<String>, public val deleted: Set<String>) {
    /** True if the step changed nothing. */
    public val isEmpty: Boolean
        get() = changed.isEmpty() && created.isEmpty() && deleted.isEmpty()

    public companion object {
        /** No change. */
        public val NONE: MirrorChange = MirrorChange(emptySet(), emptySet(), emptySet())
    }
}

/**
 * The result of one step of the [MirrorReducer].
 *
 * @property snapshot the mirror after the step.
 * @property change what the step changed.
 * @property anomalies what the runtime's JSON contained that the schema does not explain, in plain sentences.
 */
public data class MirrorStep(public val snapshot: MirrorSnapshot, public val change: MirrorChange, public val anomalies: List<String>)
