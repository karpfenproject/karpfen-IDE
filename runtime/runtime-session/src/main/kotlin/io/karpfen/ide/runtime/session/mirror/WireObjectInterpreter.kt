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
import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.session.schema.PropertyShape
import io.karpfen.ide.runtime.session.schema.RelationShape
import io.karpfen.ide.runtime.session.schema.TypeSchema

/**
 * One object tree of the runtime's JSON as mirror values.
 *
 * @property root the reference to the tree's root object.
 * @property objects every keyed object of the tree by id, at its first occurrence, in the order the tree shows them.
 * @property containment every keyed object the tree embeds through `has` relations, with its keyed owner.
 * @property anomalies what the schema does not explain, in plain sentences.
 */
internal data class InterpretedTree(
    val root: ObjectRef,
    val objects: Map<String, MirrorObject>,
    val containment: Map<String, Containment>,
    val anomalies: List<String>,
)

/**
 * Turns an object tree of the runtime's JSON, a pushed `value` or a root of a snapshot, into mirror values with the
 * [schema] of the domain metamodel, as the runtime's `ModelJsonWriter` wrote it: `__id__` and `__type__` first, then
 * the properties and relations present on the instance, `has` and `knows` targets alike as full nested objects.
 *
 * - `__type__` selects the type. A key among its simple properties is a property: a number, a string or a boolean, an
 *   array a list. A key among its relations is a relation with that relation's association: `null` an empty atomic
 *   relation, an object one target, an array a list whose `null` entries are [ObjectRef.Missing]. A value of another
 *   kind than the schema declares is kept as the JSON shows it and reported as an anomaly.
 * - Another key is kept, classified by its JSON shape (objects and arrays holding objects as relations without
 *   association, everything else as properties), and reported as an anomaly. An unknown `__type__` keeps all its fields
 *   that way and is reported once per object.
 * - Every nested object with an id is a keyed object of the tree; the first occurrence of an id wins, because the
 *   runtime writes every reference to an object in full, from the same model state. Objects without an id stay inline
 *   as [ObjectRef.Anonymous].
 * - Containment is recorded only for targets of relations the schema declares as `has`, never for unknown associations.
 * - Ids outside [modelObjectIds] get origin [ObjectOrigin.RUNTIME]; anonymous objects take the origin of the nearest
 *   keyed object that holds them, anonymous roots [ObjectOrigin.MODEL]. Every object gets the given revision.
 *
 * Stateless; each call of [interpret] uses a confined builder.
 *
 * @param schema the schema of the domain metamodel.
 * @param modelObjectIds the ids the deployed model declares.
 */
internal class WireObjectInterpreter(private val schema: TypeSchema, private val modelObjectIds: Set<String>) {

    /** The tree whose root is [root], every object stamped with [revision]. */
    fun interpret(root: WireValue.WireObject, revision: Long): InterpretedTree = TreeReader(revision).read(root)

    /**
     * Reads one tree; confined to one call of [interpret].
     *
     * @param revision the revision every object gets.
     */
    private inner class TreeReader(private val revision: Long) {
        /** The ids of the keyed objects met so far, in order. */
        private val order = LinkedHashSet<String>()

        /** The keyed objects read so far. */
        private val objects = HashMap<String, MirrorObject>()

        /** The containment of the embedded keyed objects met so far. */
        private val containment = LinkedHashMap<String, Containment>()

        /** The anomalies met so far. */
        private val anomalies = ArrayList<String>()

        /** The tree of [root]. */
        fun read(root: WireValue.WireObject): InterpretedTree {
            val rootRef = target(root, holder = null, inheritedOrigin = ObjectOrigin.MODEL)
            return InterpretedTree(rootRef, order.associateWithTo(LinkedHashMap()) { objects.getValue(it) }, containment, anomalies)
        }

        /**
         * The reference to the nested object [value]: a keyed object is read at its first occurrence and gets [holder]
         * as its containment if it is embedded; an anonymous one is read inline with [inheritedOrigin].
         */
        private fun target(value: WireValue.WireObject, holder: Containment?, inheritedOrigin: ObjectOrigin): ObjectRef {
            val id = idOf(value)
            if (id.isEmpty()) return ObjectRef.Anonymous(convert(value, id, holder, inheritedOrigin))
            if (holder != null) containment.putIfAbsent(id, holder)
            if (order.add(id)) objects[id] = convert(value, id, holder = null, origin = originOf(id))
            return ObjectRef.Keyed(id)
        }

        /** The id of [value]: its string `__id__`, or empty with an anomaly when it has none. */
        private fun idOf(value: WireValue.WireObject): String {
            val id = value[WireNames.OBJECT_ID_MEMBER]
            if (id !is WireValue.WireString) anomalies += "An object of the runtime's JSON has no string ${WireNames.OBJECT_ID_MEMBER}; it is kept without id."
            return (id as? WireValue.WireString)?.value.orEmpty()
        }

        /** The origin of the keyed object [id]. */
        private fun originOf(id: String): ObjectOrigin = if (id in modelObjectIds) ObjectOrigin.MODEL else ObjectOrigin.RUNTIME

        /**
         * The mirror object of [value] with [id] and [origin]. [holder] is the containment that keyed objects embedded
         * in an anonymous [value] get.
         */
        private fun convert(value: WireValue.WireObject, id: String, holder: Containment?, origin: ObjectOrigin): MirrorObject {
            val typeName = (value[WireNames.TYPE_MEMBER] as? WireValue.WireString)?.value
            if (typeName == null) anomalies += "The object ${describe(id)} has no string ${WireNames.TYPE_MEMBER}."
            val type = typeName?.let(schema::type)
            if (typeName != null && type == null) anomalies += "The object ${describe(id)} has the type '$typeName', which the metamodel does not declare."
            val properties = ArrayList<MirrorProperty>()
            val relations = ArrayList<MirrorRelation>()
            val childOrigin = if (id.isEmpty()) origin else originOf(id)
            value.fields.filter { it.name != WireNames.OBJECT_ID_MEMBER && it.name != WireNames.TYPE_MEMBER }.forEach { field ->
                val propertyShape = type?.property(field.name)
                val relationShape = type?.relation(field.name)
                when {
                    propertyShape != null -> property(id, propertyShape, field.value)?.let { properties += it }
                    relationShape != null -> {
                        val childHolder = when {
                            relationShape.association != AssociationType.EMBEDDED -> null
                            id.isEmpty() -> holder
                            else -> Containment(id, field.name)
                        }
                        relations += relation(id, relationShape, field.value, childHolder, childOrigin)
                    }
                    else -> {
                        if (type != null) anomalies += "The object ${describe(id)} of type '${type.name}' has the key '${field.name}', which its type does not declare."
                        unclassified(id, field.name, field.value, childOrigin, properties, relations)
                    }
                }
            }
            return MirrorObject(id, typeName.orEmpty(), properties, relations, origin, revision)
        }

        /** The property [shape] of the object [id] from [value], or null for a value that is no property at all. */
        private fun property(id: String, shape: PropertyShape, value: WireValue): MirrorProperty? {
            val read = when (value) {
                is WireValue.WireArray -> {
                    if (!shape.isList) anomalies += "The property '${shape.key}' of ${describe(id)} is no list, but the runtime wrote a list."
                    PropertyValue.Many(value.items.mapNotNull { item -> scalar(id, shape, item) })
                }
                else -> {
                    if (shape.isList) anomalies += "The list property '${shape.key}' of ${describe(id)} was written as a single value."
                    scalar(id, shape, value)?.let { PropertyValue.Single(it) }
                }
            }
            return read?.let { MirrorProperty(shape.key, it) }
        }

        /** The scalar [value] of the property [shape] of [id], or null with an anomaly for a value that is no scalar. */
        private fun scalar(id: String, shape: PropertyShape, value: WireValue): ScalarValue? {
            val scalar = scalarOf(value)
            if (scalar == null) {
                anomalies += "The property '${shape.key}' of ${describe(id)} holds a value that is no number, string or boolean; it is left out."
                return null
            }
            val expected = when (shape.type) {
                SimplePropertyType.NUMBER -> scalar is ScalarValue.Number
                SimplePropertyType.STRING -> scalar is ScalarValue.Text
                SimplePropertyType.BOOLEAN -> scalar is ScalarValue.Flag
            }
            if (!expected) anomalies += "The property '${shape.key}' of ${describe(id)} holds a value of another type than the metamodel's."
            return scalar
        }

        /** The relation [shape] of [id] from [value]; embedded keyed targets get [holder]. */
        private fun relation(
            id: String,
            shape: RelationShape,
            value: WireValue,
            holder: Containment?,
            childOrigin: ObjectOrigin,
        ): MirrorRelation {
            val targets = when (value) {
                is WireValue.WireNull -> emptyList()
                is WireValue.WireObject -> {
                    if (shape.isList) anomalies += "The list relation '${shape.key}' of ${describe(id)} was written as a single object."
                    listOf(target(value, holder, childOrigin))
                }
                is WireValue.WireArray -> {
                    if (!shape.isList) anomalies += "The relation '${shape.key}' of ${describe(id)} is no list, but the runtime wrote a list."
                    entries(id, shape.key, value, holder, childOrigin)
                }
                else -> {
                    anomalies += "The relation '${shape.key}' of ${describe(id)} holds a value that is no object; it is left empty."
                    emptyList()
                }
            }
            return MirrorRelation(shape.key, shape.association, shape.isList, targets)
        }

        /** The targets of the list [value] of the relation [key] of [id]: objects and `null` entries. */
        private fun entries(
            id: String,
            key: String,
            value: WireValue.WireArray,
            holder: Containment?,
            childOrigin: ObjectOrigin,
        ): List<ObjectRef> =
            value.items.mapNotNull { item ->
                when (item) {
                    is WireValue.WireObject -> target(item, holder, childOrigin)
                    is WireValue.WireNull -> ObjectRef.Missing
                    else -> {
                        anomalies += "The relation '$key' of ${describe(id)} holds an entry that is no object; it is left out."
                        null
                    }
                }
            }

        /**
         * Keeps the field [key] that no schema member explains, classified by its JSON shape: objects, `null` and arrays
         * holding objects as relations without association (never containment), everything else as properties.
         */
        private fun unclassified(
            id: String,
            key: String,
            value: WireValue,
            childOrigin: ObjectOrigin,
            properties: MutableList<MirrorProperty>,
            relations: MutableList<MirrorRelation>,
        ) {
            when (value) {
                is WireValue.WireObject -> relations += MirrorRelation(key, null, false, listOf(target(value, null, childOrigin)))
                is WireValue.WireNull -> relations += MirrorRelation(key, null, false, emptyList())
                is WireValue.WireArray ->
                    if (value.items.any { it is WireValue.WireObject }) {
                        relations += MirrorRelation(key, null, true, entries(id, key, value, null, childOrigin))
                    } else {
                        properties += MirrorProperty(key, PropertyValue.Many(value.items.mapNotNull(::scalarOf)))
                    }
                else -> scalarOf(value)?.let { properties += MirrorProperty(key, PropertyValue.Single(it)) }
            }
        }

        /** The scalar of [value], or null if it is no number, string or boolean. */
        private fun scalarOf(value: WireValue): ScalarValue? =
            when (value) {
                is WireValue.WireNumber -> ScalarValue.Number(value.value)
                is WireValue.WireString -> ScalarValue.Text(value.value)
                is WireValue.WireBoolean -> ScalarValue.Flag(value.value)
                else -> null
            }

        /** The object [id] in a sentence. */
        private fun describe(id: String): String = if (id.isEmpty()) "an anonymous object" else "'$id'"
    }
}
