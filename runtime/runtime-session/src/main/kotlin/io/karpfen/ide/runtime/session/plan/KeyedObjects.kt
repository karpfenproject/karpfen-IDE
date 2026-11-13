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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.dsl.instance.ClassTypeAtomicPropertyObject
import io.karpfen.dsl.instance.ClassTypeListPropertyObject
import io.karpfen.dsl.instance.ClassTypePropertyObject
import io.karpfen.dsl.instance.DataObject
import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.AssociationType
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One object of a model as the plan sees it. Immutable.
 *
 * @property key the object's key. `_` is a key like any other here. Only an object without a key on a reference loop
 *   has the empty key.
 * @property typeName the name of the object's type.
 */
internal data class KeyedObject(val key: String, val typeName: String)

/**
 * The objects of a model as the runtime indexes them, copied into immutable values. The walk visits the roots of the
 * model and then, recursively in relation order, the targets of their `has` relations. Each object is visited once, in
 * that pre-order. Every visited object with a non-empty key is in the index. The runtime builds the same index when it
 * activates an environment, and it keeps the last of equal keys. The IDE's syntax index treats `_` as anonymous, but
 * the runtime does not, so `_` is a key here.
 *
 * @property objects the keyed objects in pre-order, equal keys included.
 */
internal class KeyedObjects private constructor(val objects: List<KeyedObject>) {
    /** The keys of the index. */
    val ids: Set<String> = objects.mapTo(LinkedHashSet()) { it.key }

    /**
     * The type of the object with [key] that the runtime keeps, which is the last of equal keys. Returns null if no
     * object has the key.
     */
    fun typeOf(key: String): String? = objects.lastOrNull { it.key == key }?.typeName

    companion object {
        /**
         * The index of [model]. It reads relation targets through their references only, because resolving the list
         * of an unresolved reference throws.
         */
        fun of(model: Model): KeyedObjects {
            val visited: MutableSet<DataObject> = Collections.newSetFromMap(IdentityHashMap())
            val keyed = mutableListOf<KeyedObject>()
            fun visit(obj: DataObject) {
                if (!visited.add(obj)) return
                if (obj.id.isNotEmpty()) keyed += KeyedObject(obj.id, obj.ofType.name)
                obj.relations.filter { it.getPropertyType().associationType == AssociationType.EMBEDDED }
                    .flatMap { targetsOf(it) }
                    .forEach(::visit)
            }
            model.objects.forEach(::visit)
            return KeyedObjects(keyed)
        }

        /** The resolved targets of [relation], in order, through its references. */
        fun targetsOf(relation: ClassTypePropertyObject): List<DataObject> =
            when (relation) {
                is ClassTypeAtomicPropertyObject -> listOfNotNull(relation.getValueRef().obj)
                is ClassTypeListPropertyObject -> relation.getValueRefs().mapNotNull { it.obj }
                else -> emptyList()
            }
    }
}
