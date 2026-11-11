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

import io.karpfen.dsl.instance.ClassTypeAtomicPropertyObject
import io.karpfen.dsl.instance.ClassTypeListPropertyObject
import io.karpfen.dsl.instance.ClassTypePropertyObject
import io.karpfen.dsl.instance.DataObject
import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.instance.SimpleAtomicPropertyObject
import io.karpfen.dsl.instance.SimpleListPropertyObject
import io.karpfen.dsl.instance.SimplePropertyObject
import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.runtime.session.schema.TypeSchema

/**
 * Turns the analyzed dsl-tools model of a deployment into the mirror's first snapshot. The model is the text that is
 * uploaded. The seed is the IDE's prediction of what the runtime will report before it reported anything.
 *
 * dsl-tools 2.0 creates only the properties and relations that the `.kmodel` writes, with `has` relations before
 * `knows` relations. The runtime's converter does the same, so absent properties stay absent and the orders match the
 * runtime's JSON. Numbers become doubles.
 *
 * Objects are normalized by id. Relations hold [ObjectRef.Keyed] targets, and every keyed object is in
 * [MirrorSnapshot.objects] once, at its first occurrence in document order. The planner flags a model that declares an
 * id twice. Objects with an empty id live inline as [ObjectRef.Anonymous]. An unresolved entry of a list relation is
 * [ObjectRef.Missing], and an unresolved atomic relation is empty, because the runtime writes both as `null`. Every
 * object has origin [ObjectOrigin.MODEL] and revision 0.
 *
 * The association of a relation is taken from the [TypeSchema] of the domain metamodel. The runtime's JSON is
 * interpreted with the same source, so a seed and the runtime's read-back of the same model are equal. A keyed object
 * reached only through `knows` relations is added after the embedded tree, without containment.
 *
 * Stateless.
 */
public object MirrorSeeder {
    /** The snapshot at revision 0 of [model], whose types [schema] describes. */
    public fun seed(model: Model, schema: TypeSchema): MirrorSnapshot = SeedBuilder(schema).build(model)

    /**
     * Builds one seed. Confined to one call of [seed].
     *
     * @param schema the schema of the domain metamodel.
     */
    private class SeedBuilder(private val schema: TypeSchema) {
        /** The ids of the keyed objects in the order they were first met. */
        private val order = LinkedHashSet<String>()

        /** The keyed objects converted so far. */
        private val objects = HashMap<String, MirrorObject>()

        /** The containment of every embedded keyed object, at its first embedding. */
        private val containment = LinkedHashMap<String, Containment>()

        /** Keyed objects met only as the targets of references so far, converted after the embedded tree. */
        private val referenced = ArrayDeque<DataObject>()

        /** The snapshot of [model]. */
        fun build(model: Model): MirrorSnapshot {
            val roots = model.objects.map { target(it, holder = null) }
            while (referenced.isNotEmpty()) {
                val next = referenced.removeFirst()
                if (next.id !in order) register(next)
            }
            return MirrorSnapshot(order.associateWithTo(LinkedHashMap()) { objects.getValue(it) }, roots, containment)
        }

        /**
         * The reference to [target]. The target is converted if it is anonymous or met for the first time. [holder]
         * is the containment a keyed target gets. It is null when the target is not embedded.
         */
        private fun target(target: DataObject, holder: Containment?): ObjectRef {
            if (target.id.isEmpty()) return ObjectRef.Anonymous(convert(target, holder))
            if (holder != null) containment.putIfAbsent(target.id, holder)
            if (target.id !in order) register(target)
            return ObjectRef.Keyed(target.id)
        }

        /** Converts the keyed object [target] and records it under its id. */
        private fun register(target: DataObject) {
            order += target.id
            objects[target.id] = convert(target, holder = null)
        }

        /**
         * The mirror object of [source]. [holder] is the containment that keyed objects embedded in an anonymous
         * [source] get. It names the keyed owner that embeds [source] and its relation.
         */
        private fun convert(source: DataObject, holder: Containment?): MirrorObject =
            MirrorObject(
                id = source.id,
                typeName = source.ofType.name,
                properties = source.properties.map(::property),
                relations = source.relations.map { relation(source, it, holder) },
                origin = ObjectOrigin.MODEL,
                revision = 0,
            )

        /** The mirror property of [source]. */
        private fun property(source: SimplePropertyObject): MirrorProperty {
            val type = source.getValueType()
            val value = when (source) {
                is SimpleListPropertyObject -> PropertyValue.Many(source.getValues().map { scalar(type, it) })
                is SimpleAtomicPropertyObject -> PropertyValue.Single(scalar(type, source.getValue()))
                else -> PropertyValue.Many(emptyList())
            }
            return MirrorProperty(source.key, value)
        }

        /** The mirror relation of [source], a relation of [owner]. */
        private fun relation(owner: DataObject, source: ClassTypePropertyObject, ownerHolder: Containment?): MirrorRelation {
            val association = schema.type(owner.ofType.name)?.relation(source.key)?.association
            val holder = when {
                association != AssociationType.EMBEDDED -> null
                owner.id.isEmpty() -> ownerHolder
                else -> Containment(owner.id, source.key)
            }
            val targets = when (source) {
                is ClassTypeListPropertyObject -> source.getValueRefs().map { ref ->
                    ref.obj?.let { reach(it, association, holder) } ?: ObjectRef.Missing
                }
                is ClassTypeAtomicPropertyObject -> listOfNotNull(source.getValue()?.let { reach(it, association, holder) })
                else -> emptyList()
            }
            return MirrorRelation(source.key, association, source.isList(), targets)
        }

        /**
         * The reference to [target] through a relation with [association]. Embedded targets are converted in place.
         * Keyed targets of references are converted later, after the embedded tree.
         */
        private fun reach(target: DataObject, association: AssociationType?, holder: Containment?): ObjectRef {
            if (association == AssociationType.EMBEDDED || target.id.isEmpty()) return target(target, holder)
            if (target.id !in order) referenced += target
            return ObjectRef.Keyed(target.id)
        }

        /** The scalar of the dsl-tools value [value] of a property of [type]. */
        private fun scalar(type: SimplePropertyType, value: Any): ScalarValue =
            when (type) {
                SimplePropertyType.NUMBER -> ScalarValue.Number((value as? Number)?.toDouble() ?: value.toString().toDouble())
                SimplePropertyType.STRING -> ScalarValue.Text(value.toString())
                SimplePropertyType.BOOLEAN -> ScalarValue.Flag(value as? Boolean ?: value.toString().toBoolean())
            }
    }
}
