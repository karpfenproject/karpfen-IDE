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
package io.karpfen.ide.runtime.session.fixtures

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
import io.karpfen.ide.runtime.protocol.golden.FakeAssociation
import io.karpfen.ide.runtime.protocol.golden.FakeObject
import io.karpfen.ide.runtime.protocol.golden.FakeProperty
import io.karpfen.ide.runtime.protocol.golden.FakeRelation
import io.karpfen.ide.runtime.protocol.golden.FakeScalar
import io.karpfen.ide.runtime.protocol.golden.FakeTarget
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.message.RuntimePhase

/**
 * A dsl-tools 2.0 model as the runtime holds it. With it, the runtime's JSON of any analyzed model comes from the one
 * port of the runtime's writers, [RuntimeWireWriter].
 *
 * The model's root objects become [FakeObject]s with the properties and relations that the `.kmodel` writes. They are
 * in instance order, which puts `has` before `knows`, as the readers create them. Numbers become doubles. Embedded and
 * anonymous objects are nested. `knows` targets are referred to by id and resolved through [objects] when written. An
 * unresolved atomic relation is empty and an unresolved list entry is `null`, as the runtime writes them.
 *
 * The model is converted when this is built, and nothing keeps the dsl-tools model. Immutable.
 *
 * @param model the analyzed model.
 */
public class ModelWireText(model: Model) {
    /** Every keyed object by id, at its first occurrence in document order. */
    private val byId = LinkedHashMap<String, FakeObject>()

    /** The model's root objects in model order. */
    public val roots: List<FakeObject> = Converter(byId).roots(model)

    /** Every keyed object of the model by id, the targets of `knows` relations included. */
    public val objects: Map<String, FakeObject>
        get() = byId

    /**
     * The runtime's JSON of the object [id], as an `objectChanged` push carries it.
     *
     * @throws IllegalArgumentException if the model has no object [id].
     * @throws IllegalStateException if the object reaches a reference loop, as the runtime refuses it.
     */
    public fun objectJson(id: String): String {
        val obj = requireNotNull(byId[id]) { "the model has no object '$id'" }
        return RuntimeWireWriter.objectJson(obj, byId::get)
    }

    /** The runtime's JSON of every root object, as a snapshot's `roots` holds them. */
    public fun rootsJson(): List<String> = roots.map { RuntimeWireWriter.objectJson(it, byId::get) }

    /** The body of a snapshot of this model in [environmentKey] at [phase] and [revision] with [activeStates]. */
    public fun snapshot(
        environmentKey: String,
        phase: RuntimePhase,
        revision: Long,
        activeStates: Map<String, List<String>> = emptyMap(),
    ): String = RuntimeWireWriter.snapshot(environmentKey, phase, revision, activeStates, rootsJson())

    /**
     * Converts one model. Confined to the construction.
     *
     * @param byId receives every keyed object.
     */
    private class Converter(private val byId: MutableMap<String, FakeObject>) {
        /**
         * Keyed targets of `knows` relations. They are converted after the embedded tree, if the tree does not hold
         * them.
         */
        private val referenced = ArrayDeque<DataObject>()

        /** The root objects of [model]. Every keyed object is recorded on the way. */
        fun roots(model: Model): List<FakeObject> {
            val roots = model.objects.map(::convert)
            while (referenced.isNotEmpty()) {
                val next = referenced.removeFirst()
                if (next.id !in byId) convert(next)
            }
            return roots
        }

        /** The fake of [source], recorded under its id if it has one and is met for the first time. */
        private fun convert(source: DataObject): FakeObject {
            val fake = FakeObject(source.id, source.ofType.name, source.properties.map(::property), source.relations.map(::relation))
            if (source.id.isNotEmpty()) byId.putIfAbsent(source.id, fake)
            return fake
        }

        /** The fake of the property [source]. */
        private fun property(source: SimplePropertyObject): FakeProperty {
            val type = source.getValueType()
            return when (source) {
                is SimpleListPropertyObject -> FakeProperty.Many(source.key, source.getValues().map { scalar(type, it) })
                is SimpleAtomicPropertyObject -> FakeProperty.One(source.key, scalar(type, source.getValue()))
                else -> FakeProperty.Many(source.key, emptyList())
            }
        }

        /** The fake of the relation [source]. */
        private fun relation(source: ClassTypePropertyObject): FakeRelation {
            val association = when (source.getPropertyType().associationType) {
                AssociationType.EMBEDDED -> FakeAssociation.HAS
                AssociationType.LINK -> FakeAssociation.KNOWS
            }
            return when (source) {
                is ClassTypeListPropertyObject ->
                    FakeRelation.Many(source.key, association, source.getValueRefs().map { ref -> ref.obj?.let { target(it, association) } })
                is ClassTypeAtomicPropertyObject ->
                    FakeRelation.One(source.key, association, source.getValue()?.let { target(it, association) })
                else -> FakeRelation.Many(source.key, association, emptyList())
            }
        }

        /**
         * The target [obj] of a relation with [association]. It is nested if it is embedded or anonymous, and referred
         * to by id otherwise.
         */
        private fun target(obj: DataObject, association: FakeAssociation): FakeTarget {
            if (association == FakeAssociation.HAS || obj.id.isEmpty()) return FakeTarget.Nested(convert(obj))
            if (obj.id !in byId) referenced += obj
            return FakeTarget.Known(obj.id)
        }

        /** The fake scalar of the dsl-tools value [value] of a property of [type]. */
        private fun scalar(type: SimplePropertyType, value: Any): FakeScalar =
            when (type) {
                SimplePropertyType.NUMBER -> FakeScalar.Number((value as? Number)?.toDouble() ?: value.toString().toDouble())
                SimplePropertyType.STRING -> FakeScalar.Text(value.toString())
                SimplePropertyType.BOOLEAN -> FakeScalar.Flag(value as? Boolean ?: value.toString().toBoolean())
            }
    }
}
