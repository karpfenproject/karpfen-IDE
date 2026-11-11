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

import io.karpfen.dsl.instance.DataObject
import io.karpfen.dsl.instance.Model
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A reference loop of a model. Each object of the loop reaches the next through a `has` or `knows` relation, and the
 * last one reaches the first. Immutable.
 *
 * @property path the objects of the loop as keyed objects, starting from the object found on it. That object is both
 *   first and last. An object without a key appears with the empty key.
 */
internal data class ReferenceLoop(val path: List<KeyedObject>) {
    /** The object found on the loop, at which the loop is reported. */
    val start: KeyedObject
        get() = path.first()

    /** The first object of the loop with a key, which a finding can point at. It is null if no object has a key. */
    val firstKeyed: KeyedObject?
        get() = path.firstOrNull { it.key.isNotEmpty() }
}

/**
 * Finds reference loops in a model by a depth-first search over object identity, anonymous objects included, with an
 * edge for every target of a `has` or a `knows` relation. The runtime writes `knows` targets as nested objects, so it
 * cannot write a model with such a loop. Stateless.
 */
internal object ReferenceLoops {
    /**
     * The first loop that the search from the roots of [model] finds, in root and relation order. Returns null if
     * there is none.
     */
    fun firstIn(model: Model): ReferenceLoop? {
        val finished: MutableSet<DataObject> = Collections.newSetFromMap(IdentityHashMap())
        val onPath = IdentityHashMap<DataObject, Int>()
        val path = ArrayList<DataObject>()
        fun visit(obj: DataObject): ReferenceLoop? {
            onPath[obj]?.let { index -> return ReferenceLoop((path.subList(index, path.size) + obj).map(::keyed)) }
            if (obj in finished) return null
            onPath[obj] = path.size
            path += obj
            for (relation in obj.relations) {
                for (target in KeyedObjects.targetsOf(relation)) {
                    visit(target)?.let { return it }
                }
            }
            path.removeAt(path.lastIndex)
            onPath.remove(obj)
            finished += obj
            return null
        }
        for (root in model.objects) {
            visit(root)?.let { return it }
        }
        return null
    }

    /** [obj] as a keyed object, with the empty key if it has none. */
    private fun keyed(obj: DataObject): KeyedObject = KeyedObject(obj.id, obj.ofType.name)
}
